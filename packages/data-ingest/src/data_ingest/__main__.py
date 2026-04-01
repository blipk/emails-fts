"""CLI entry point for the Enron email dataset parser.

Usage:
    uv run data-ingest [--db-path PATH] [--mime-dir PATH] [--mysql-dump PATH] [--validate-only]
    uv run data-ingest --clean    # Delete existing DB and start fresh
    uv run data-ingest --continue # Resume a previously interrupted import
"""

import argparse
import logging
import os
import sys
import tarfile
from importlib import resources
from pathlib import Path

from data_ingest.parser import EmailDatasetParser


def _resolve_default_paths() -> tuple[Path, Path, Path]:
    """Resolve default paths for bundled data sources and extraction directory.

    Returns:
        Tuple of (mysql_dump_path, mime_tar_path, extract_dir) from the package's data directory.
    """
    data_dir = resources.files("data_ingest").joinpath("data")
    mysql_dump = Path(str(data_dir.joinpath("enron-mysqldump_v5.sql.gz")))
    mime_tar = Path(str(data_dir.joinpath("enron_mail_20150507.tar.gz")))
    extract_dir = Path(str(data_dir.joinpath("extracted")))
    return mysql_dump, mime_tar, extract_dir


def _setup_logging(verbose: bool) -> None:
    """Configure logging with appropriate level and format.

    Args:
        verbose: If True, set DEBUG level; otherwise INFO.
    """
    level = logging.DEBUG if verbose else logging.INFO
    logging.basicConfig(
        level=level,
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
        datefmt="%H:%M:%S",
    )


def _count_files(directory: Path) -> int:
    """Count all regular files in a directory tree.

    Args:
        directory: Root directory to count files in.

    Returns:
        Total number of files found.
    """
    count = 0
    for entry in directory.rglob("*"):
        if entry.is_file():
            count += 1
    return count


def _resolve_mode(db_path: Path, force_clean: bool, force_continue: bool, logger: logging.Logger) -> str:
    """Determine whether to run in clean or continue mode.

    If neither flag is set, checks for an existing database and prompts the user.

    Args:
        db_path: Path to the SQLite database.
        force_clean: If True, always use clean mode.
        force_continue: If True, always use continue mode.
        logger: Logger instance.

    Returns:
        "clean" or "continue".
    """
    if force_clean:
        return "clean"
    if force_continue:
        return "continue"

    if db_path.exists() and db_path.stat().st_size > 0:
        logger.info("Existing database found at %s", db_path)
        while True:
            try:
                choice = input("Database already exists. [c]ontinue previous import or [r]estart clean? (c/r): ").strip().lower()
            except EOFError:
                return "clean"
            if choice in ("c", "continue"):
                return "continue"
            if choice in ("r", "restart", "clean"):
                return "clean"
            print("Please enter 'c' to continue or 'r' to restart clean.")
    return "clean"


def main() -> int:
    """Run the dataset parser CLI.

    Returns:
        Exit code (0 for success, 1 for failure).
    """
    default_mysql_dump, default_mime_tar, default_extract_dir = _resolve_default_paths()
    default_db = str(Path.cwd() / "enron_emails.db")

    arg_parser = argparse.ArgumentParser(
        description="Parse Enron MIME email files and MySQL employee dump into SQLite.",
        prog="data-ingest",
    )
    arg_parser.add_argument(
        "--db-path",
        default=default_db,
        help=f"SQLite database output path (default: {default_db})",
    )
    arg_parser.add_argument(
        "--mime-dir",
        default=None,
        help="Path to extracted maildir directory. If not provided, extracts from bundled tar.gz.",
    )
    arg_parser.add_argument(
        "--mime-tar",
        default=str(default_mime_tar),
        help=f"Path to MIME tar.gz archive (default: bundled {default_mime_tar.name})",
    )
    arg_parser.add_argument(
        "--extract-dir",
        default=str(default_extract_dir),
        help=f"Directory for extracted MIME data (default: {default_extract_dir})",
    )
    arg_parser.add_argument(
        "--mysql-dump",
        default=str(default_mysql_dump),
        help=f"Path to MySQL dump .sql.gz (default: bundled {default_mysql_dump.name})",
    )
    arg_parser.add_argument(
        "--validate-only",
        action="store_true",
        help="Only run validation on an existing database, skip import.",
    )
    arg_parser.add_argument(
        "--skip-emails",
        action="store_true",
        help="Skip MIME email import (only import employees).",
    )
    arg_parser.add_argument(
        "--skip-employees",
        action="store_true",
        help="Skip employee import (only import MIME emails).",
    )

    mode_group = arg_parser.add_mutually_exclusive_group()
    mode_group.add_argument(
        "--clean",
        action="store_true",
        help="Delete existing database and start fresh.",
    )
    mode_group.add_argument(
        "--continue",
        dest="continue_mode",
        action="store_true",
        help="Resume a previously interrupted import.",
    )

    arg_parser.add_argument(
        "-v", "--verbose",
        action="store_true",
        help="Enable debug logging.",
    )

    args = arg_parser.parse_args()
    _setup_logging(args.verbose)

    logger = logging.getLogger("data_ingest")
    db_path = Path(args.db_path)

    parser = EmailDatasetParser(db_path=args.db_path)
    try:
        if args.validate_only:
            logger.info("Running validation only on %s", args.db_path)
            result = parser.validate()
            if result.is_valid:
                logger.info("Validation passed")
            else:
                logger.warning("Validation failed with %d issues:", len(result.issues))
                for issue in result.issues:
                    logger.warning("  - %s", issue)
            return 0 if result.is_valid else 1

        # Resolve operating mode
        mode = _resolve_mode(db_path, args.clean, args.continue_mode, logger)

        if mode == "clean" and db_path.exists():
            logger.info("Clean mode: deleting existing database %s", db_path)
            parser.close()
            os.remove(db_path)
            parser = EmailDatasetParser(db_path=args.db_path)

        continue_mode = mode == "continue"
        if continue_mode:
            logger.info("Continue mode: resuming from last imported email")

        # Initialise schema (IF NOT EXISTS so safe for continue mode)
        parser.init_database()

        # Import employees
        if not args.skip_employees:
            mysql_dump_path = Path(args.mysql_dump)
            if not mysql_dump_path.exists():
                logger.error("MySQL dump not found: %s", mysql_dump_path)
                return 1
            logger.info("Importing employee data from %s", mysql_dump_path)
            emp_result = parser.import_employee_data(str(mysql_dump_path))
            logger.info(
                "Employees: %d/%d succeeded, %d errors",
                emp_result.success_count, emp_result.total_processed, emp_result.error_count,
            )

        # Import MIME emails
        if not args.skip_emails:
            mime_dir = args.mime_dir
            extract_dir = Path(args.extract_dir)

            if mime_dir is None:
                maildir_path = extract_dir / "maildir"
                if maildir_path.is_dir():
                    logger.info("Using previously extracted maildir at %s", maildir_path)
                    mime_dir = str(maildir_path)
                else:
                    mime_tar_path = Path(args.mime_tar)
                    if not mime_tar_path.exists():
                        logger.error("MIME tar archive not found: %s", mime_tar_path)
                        return 1

                    logger.info("Extracting MIME archive %s to %s (this may take a while)...", mime_tar_path, extract_dir)
                    extract_dir.mkdir(parents=True, exist_ok=True)
                    with tarfile.open(mime_tar_path, "r:gz") as tar:
                        tar.extractall(path=str(extract_dir), filter="data")
                    mime_dir = str(maildir_path)
                    logger.info("Extracted to %s", mime_dir)

            mime_root = Path(mime_dir)
            file_count = _count_files(mime_root)
            logger.info("Found %d files in %s", file_count, mime_dir)

            logger.info("Importing MIME emails from %s", mime_dir)
            email_result = parser.import_mime_emails(mime_dir, continue_mode=continue_mode)
            logger.info(
                "Emails: %d/%d succeeded, %d skipped, %d errors",
                email_result.success_count,
                email_result.total_processed,
                email_result.skip_count,
                email_result.error_count,
            )

        # Resolve thread references (post-import pass)
        if not args.skip_emails:
            logger.info("Resolving thread references from quoted blocks...")
            resolved = parser.resolve_thread_references()
            logger.info("Resolved %d thread references", resolved)

        # Validate
        logger.info("Running validation...")
        val_result = parser.validate()
        if val_result.is_valid:
            logger.info("Validation passed")
        else:
            logger.warning("Validation completed with %d issues:", len(val_result.issues))
            for issue in val_result.issues:
                logger.warning("  - %s", issue)

        return 0

    except KeyboardInterrupt:
        logger.info("Interrupted by user (resume with --continue)")
        return 130
    except Exception as exc:
        logger.error("Fatal error: %s", exc, exc_info=True)
        return 1
    finally:
        parser.close()


if __name__ == "__main__":
    sys.exit(main())
