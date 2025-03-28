package app.soundcheck.content.internal;

import app.soundcheck.media.MediaFailed;
import app.soundcheck.media.MediaReady;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Content's reaction to the worker's events: {@code MediaReady} publishes the post that was
 * waiting for the clip, {@code MediaFailed} fails it. The listeners are not restricted to a role: the
 * events are published in the worker process, and a publication is only stored for listeners that exist
 * in the process that publishes it.
 */
@Component
class MediaEvents {

    private final PostService posts;

    MediaEvents(PostService posts) {
        this.posts = posts;
    }

    @ApplicationModuleListener
    void on(MediaReady event) {
        posts.mediaReady(event.mediaId());
    }

    @ApplicationModuleListener
    void on(MediaFailed event) {
        posts.mediaFailed(event.mediaId());
    }
}
