-- A new ranking parameter, authorCapPerSession: at most this many posts by one author in a
-- feed session while other authors have posts. Every stored config must name every parameter
-- (RankingConfig.Params.NAMES), so the existing ones get the default, 5.
update feed.ranking_config
   set params = params || '{"authorCapPerSession": 5}'::jsonb
 where not params ? 'authorCapPerSession';
