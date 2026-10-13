# Experiments

This campaign measured the code as it is (commit `cfce53c`); it ran **no experiments**. An experiment here means
one change at a time, measured with the same scenario, rate and dataset as the run it is compared with, kept only
if it beats the run-to-run spread ([REPORT.md](REPORT.md#results-medium): 1.6 % for gigs' p95, 2.0 % for For
You's saturated first page).

## Not tried, suggested by this campaign's data
| Idea | Why | Evidence |
|---|---|---|
| A cheaper For You candidate statement | Its most common text is 2,652.7 s of feed run 1's 3,291.1 s DB time; 178.050 ms alone and warm, 1,763.77 ms mean under load | [pg-top](runs/20261013T025347Z-feed-medium-pg-top.txt), [plan](runs/20261013T035929Z-explain-feed_candidates-medium.txt) |
| A re-ranker whose cost does not grow with the candidate pool | `MmrReranker` and the set lookups inside it are the API's top methods (22.50 % `Set12.contains`, 7.83 % `rerank`) | [JFR](runs/20261013T025424Z-jfr-feed-medium.txt) |
| A separate pool or a concurrency limit for For You | In the mixed run every endpoint queues behind For You (p95 28.7–54.7 s) while Following, gigs and events meet their targets on their own | [mixed](runs/20261013T035248Z-mixed-medium.json) |
| The profile's post count | Second in the mixed run's top statements (981 calls, mean 337.18 ms); 60.758 ms alone | [pg-top](runs/20261013T035248Z-mixed-medium-pg-top.txt), [plan](runs/20261013T035932Z-explain-profile_post_count-medium.txt) |
