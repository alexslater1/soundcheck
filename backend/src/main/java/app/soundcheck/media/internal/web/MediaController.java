package app.soundcheck.media.internal.web;

import app.soundcheck.media.internal.UploadService;
import app.soundcheck.media.internal.UploadService.CompletedPart;
import app.soundcheck.media.internal.UploadService.CreateUpload;
import app.soundcheck.media.internal.UploadService.Playback;
import app.soundcheck.media.internal.UploadService.UploadTicket;
import app.soundcheck.shared.web.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Media: open an upload, complete it, abort it, read playback info. */
@RestController
@RequestMapping("/api/v1/media")
@Tag(name = "media", description = "Direct-to-storage uploads and playback")
public class MediaController {

    private final UploadService uploads;

    public MediaController(UploadService uploads) {
        this.uploads = uploads;
    }

    @PostMapping("/uploads")
    @Operation(
            operationId = "createUpload",
            summary = "Open an upload: returns one presigned PUT (SINGLE) or a URL per part (MULTIPART)")
    public ResponseEntity<UploadTicket> create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateUploadRequest body) {
        if (idempotencyKey != null) {
            try {
                UUID.fromString(idempotencyKey);
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("IDEMPOTENCY_KEY_INVALID", "Idempotency-Key must be a UUID");
            }
        }
        UploadTicket ticket = uploads.create(
                userId(jwt),
                new CreateUpload(
                        body.kind(),
                        body.contentType(),
                        body.bytes(),
                        body.durationMs(),
                        body.width(),
                        body.height(),
                        body.clientMeta()),
                idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(ticket);
    }

    @PostMapping("/uploads/{mediaId}/complete")
    @Operation(operationId = "completeUpload", summary = "Tell the API the bytes are uploaded (idempotent)")
    public ResponseEntity<CompleteResponse> complete(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID mediaId,
            @Valid @RequestBody(required = false) CompleteRequest body) {
        List<CompletedPart> parts = body == null || body.parts() == null
                ? List.of()
                : body.parts().stream()
                        .map(p -> new CompletedPart(p.partNumber(), p.etag()))
                        .toList();
        return ResponseEntity.accepted().body(new CompleteResponse(uploads.complete(userId(jwt), mediaId, parts)));
    }

    @DeleteMapping("/uploads/{mediaId}")
    @Operation(operationId = "abortUpload", summary = "Abandon an upload that is still in progress")
    public ResponseEntity<Void> abort(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID mediaId) {
        uploads.abort(userId(jwt), mediaId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{mediaId}")
    @Operation(operationId = "getMedia", summary = "Playback info (the owner sees every status; others only READY)")
    public Playback playback(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID mediaId) {
        return uploads.playback(userId(jwt), mediaId);
    }

    public record CreateUploadRequest(
            @NotBlank @Schema(allowableValues = {"VIDEO", "IMAGE"}) String kind,
            @NotBlank @Size(max = 100) String contentType,
            @NotNull @Min(1) Long bytes,
            @Schema(nullable = true) @Min(0) Integer durationMs,
            @Schema(nullable = true) @Min(1) Integer width,
            @Schema(nullable = true) @Min(1) Integer height,
            @Schema(nullable = true) @Size(max = 20) Map<String, Object> clientMeta) {}

    public record PartRequest(@NotNull @Min(1) Integer partNumber, @NotBlank String etag) {}

    public record CompleteRequest(@Schema(nullable = true) @Size(max = 10_000) List<@Valid PartRequest> parts) {}

    public record CompleteResponse(String status) {}

    static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
