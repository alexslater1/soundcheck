package app.soundcheck.social.internal;

import app.soundcheck.identity.ProfileMadePublic;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/** Social's reaction to identity's events about follow requests. */
@Component
class FollowRequestListeners {

    private final UserGraphService graph;

    FollowRequestListeners(UserGraphService graph) {
        this.graph = graph;
    }

    /** A public profile has no gate: the requests still waiting are accepted. */
    @ApplicationModuleListener
    void on(ProfileMadePublic event) {
        graph.acceptAllWaiting(event.userId());
    }
}
