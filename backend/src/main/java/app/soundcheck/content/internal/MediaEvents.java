package app.soundcheck.content.internal;

import app.soundcheck.media.MediaFailed;
import app.soundcheck.media.MediaReady;
import app.soundcheck.media.MediaRequeued;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Content's reaction to the media events: {@code MediaReady} publishes the post that was
 * waiting for the clip, {@code MediaFailed} fails it, and {@code MediaRequeued} (an operator's retry)
 * brings a post that failed because of the clip back to waiting for it. The listeners are not
 * restricted to a role: the worker publishes the first two, the API the third, and a publication is
 * only stored for listeners that exist in the process that publishes it.
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

    @ApplicationModuleListener
    void on(MediaRequeued event) {
        posts.mediaRequeued(event.mediaId());
    }
}
