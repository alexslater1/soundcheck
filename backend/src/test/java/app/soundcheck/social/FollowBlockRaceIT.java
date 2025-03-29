package app.soundcheck.social;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.support.IntegrationTest;
import app.soundcheck.support.Parallel;
import app.soundcheck.support.TestApi;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;

/**
 * A follow and a block of the same two people at the same moment: whichever wins, no
 * follow may be left across the block. The two requests take the same advisory lock, so one of them
 * sees the other's result.
 */
class FollowBlockRaceIT extends IntegrationTest {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    DSLContext dsl;

    @Test
    void aFollowRacingABlockNeverSurvivesIt() throws Exception {
        TestApi api = new TestApi(rest);
        for (int round = 0; round < 12; round++) {
            TestApi.User blocker = api.register("raceblock");
            TestApi.User follower = api.register("racefollow");

            Parallel.run(
                    2,
                    i -> i == 0
                            ? api.put("/api/v1/users/" + blocker.id() + "/follow", null, follower)
                                    .getStatusCode()
                            : api.put("/api/v1/users/" + follower.id() + "/block", null, blocker)
                                    .getStatusCode());

            assertThat(dsl.fetchCount(dsl.selectFrom("social.user_block")
                            .where("blocker_id = ? and blocked_id = ?", blocker.id(), follower.id())))
                    .isEqualTo(1);
            assertThat(dsl.fetchCount(dsl.selectFrom("social.user_follow")
                            .where("follower_id = ? and followee_id = ?", follower.id(), blocker.id())))
                    .as("round %d: a follow across a block", round)
                    .isZero();
            // And afterwards the blocked user is told the blocker does not exist.
            assertThat(api.put("/api/v1/users/" + blocker.id() + "/follow", null, follower)
                            .getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }
    }
}
