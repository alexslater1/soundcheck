"""Demo seeding: personas, my real gig history and clips, engagement.

Everything goes through the real API (a small httpx client), never straight into the database, so
the seeded data exercises the same validation, pipeline and events as real users. Re-running changes
nothing: accounts log in when they already exist, gigs resolve to the same gig, and each clip is
posted once."""
