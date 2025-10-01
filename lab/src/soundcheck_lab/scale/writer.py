"""Streaming rows into Postgres with COPY (psycopg 3), a chunk at a time so memory stays flat.

A chunk is a polars DataFrame whose columns are named and ordered as the table's; polars writes it
as
CSV (in Rust, on several threads) and psycopg streams the bytes to `COPY … FROM STDIN (FORMAT csv)`.
In CSV, an unquoted empty field is NULL, which is how polars writes a null.
"""

from __future__ import annotations

from collections.abc import Iterable

import polars as pl
import psycopg
from psycopg import sql

CHUNK_ROWS = 500_000


def copy_frames(conn: psycopg.Connection, table: str, frames: Iterable[pl.DataFrame]) -> int:
    """COPY every frame into `schema.table`; returns the number of rows written. Commits once at the
    end, so a failed load leaves nothing half-written in that table."""
    schema, name = table.split(".")
    rows = 0
    with conn.cursor() as cur:
        for frame in frames:
            if frame.height == 0:
                continue
            columns = sql.SQL(", ").join(sql.Identifier(c) for c in frame.columns)
            statement = sql.SQL("COPY {}.{} ({}) FROM STDIN (FORMAT csv)").format(
                sql.Identifier(schema), sql.Identifier(name), columns
            )
            for start in range(0, frame.height, CHUNK_ROWS):
                part = frame.slice(start, CHUNK_ROWS)
                data = part.write_csv(
                    include_header=False,
                    datetime_format="%Y-%m-%d %H:%M:%S%.6f+00",
                    float_precision=7,
                )
                with cur.copy(statement) as copy:
                    copy.write(data.encode())
                rows += part.height
    conn.commit()
    return rows
