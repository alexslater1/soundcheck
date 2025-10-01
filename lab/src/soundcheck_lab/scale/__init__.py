"""`seed-scale`: the synthetic performance dataset.

Written straight into the perf database with COPY, not through the API like `seed-demo`: 100k
users at
the auth rate limit would take days. `profiles` holds the counts, `distributions` the shapes (pure
functions on a seeded generator), `ids` the UUIDv7s, `writer` the COPY streaming and `load` the
phases.
"""
