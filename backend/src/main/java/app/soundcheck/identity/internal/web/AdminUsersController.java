package app.soundcheck.identity.internal.web;

import app.soundcheck.identity.internal.AdminUsersService;
import app.soundcheck.shared.config.ConditionalOnRole;
import app.soundcheck.shared.config.Roles;
import app.soundcheck.shared.web.Actors;
import app.soundcheck.shared.web.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The console's Users page: read-only. */
@ConditionalOnRole(Roles.API)
@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize(Actors.ADMIN_ONLY)
@Validated
@Tag(name = "admin-users", description = "Console: find accounts (operators only)")
public class AdminUsersController {

    private final AdminUsersService users;

    public AdminUsersController(AdminUsersService users) {
        this.users = users;
    }

    @GetMapping
    @Operation(operationId = "adminListUsers", summary = "Live accounts, newest first; synthetic ones are tagged")
    public Page<AdminUsersService.AdminUser> list(
            @Parameter(description = "Handle, part of a name, or an exact email")
                    @RequestParam(required = false)
                    @Size(max = 100)
                    String q,
            @Parameter(description = "true: only seed and simulator personas; false: only real accounts")
                    @RequestParam(required = false)
                    Boolean synthetic,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(50) Integer limit) {
        return users.page(q, synthetic, cursor, limit);
    }
}
