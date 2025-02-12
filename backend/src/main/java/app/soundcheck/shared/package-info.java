/**
 * Shared kernel: IDs (UUIDv7), clock, pagination/cursors, Problem Details, security
 * helpers, outbox configuration and the jOOQ-generated schema classes. An OPEN module: every other
 * module may use it, and it depends on none of them.
 */
@org.springframework.modulith.ApplicationModule(type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package app.soundcheck.shared;
