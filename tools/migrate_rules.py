"""Add the normalised restriction classification to an already-enriched display database.

Rebuilding a continent-sized pack to gain two columns is wasteful, so this migrates in place.
Packs built after this change carry the columns from the start.
"""
import argparse
import json
import sqlite3
from pathlib import Path

from restriction_kind import VERSION, classify

TABLES = ("road_rules", "node_rules")


def stored_version(db):
    row = db.execute("SELECT value FROM metadata WHERE key='restriction_version'").fetchone()
    return (row[0] or "").strip('"') if row else "0"


def columns(db, table):
    return {row[1] for row in db.execute(f'PRAGMA table_info("{table}")')}


def migrate(path):
    path = Path(path)
    db = sqlite3.connect(path)
    try:
        restamp = stored_version(db) != VERSION
        changed = {}
        for table in TABLES:
            if not db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (table,)).fetchone():
                continue
            existing = columns(db, table)
            if "kind" not in existing:
                db.execute(f'ALTER TABLE "{table}" ADD COLUMN kind INTEGER')
            if "evidence" not in existing:
                db.execute(f'ALTER TABLE "{table}" ADD COLUMN evidence TEXT')
            rows = db.execute(f'SELECT rowid,tags,kind,evidence FROM "{table}"').fetchall()
            updates = []
            for rowid, tags, kind, evidence in rows:
                if kind is not None and not restamp:
                    continue
                tags = json.loads(tags or "{}")
                rule, reason = classify(tags, tags.get("highway"))
                updates.append((rule, reason, rowid))
            db.executemany(f'UPDATE "{table}" SET kind=?,evidence=? WHERE rowid=?', updates)
            changed[table] = len(updates)
        db.execute("INSERT OR REPLACE INTO metadata VALUES(?,?)", ("restriction_version", VERSION))
        db.commit()
        if db.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
            raise ValueError("Migration left the database inconsistent")
        return changed
    finally:
        db.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("database")
    args = parser.parse_args()
    print(json.dumps(migrate(args.database)))
