package com.slackish.admin;

import com.slackish.audit.AuditLog;
import com.slackish.audit.AuditLogRepository;
import com.slackish.audit.AuditService;
import com.slackish.common.Errors;
import com.slackish.common.Uid;
import com.slackish.workspace.Membership;
import com.slackish.workspace.MembershipRepository;
import com.slackish.workspace.WorkspaceService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final WorkspaceService workspaceService;
    private final MembershipRepository memberships;
    private final AuditLogRepository auditLogs;
    private final AuditService auditService;
    private final WorkspaceExportService workspaceExportService;

    public AdminController(WorkspaceService workspaceService, MembershipRepository memberships,
                           AuditLogRepository auditLogs, AuditService auditService,
                           WorkspaceExportService workspaceExportService) {
        this.workspaceService = workspaceService;
        this.memberships = memberships;
        this.auditLogs = auditLogs;
        this.auditService = auditService;
        this.workspaceExportService = workspaceExportService;
    }

    @PostMapping("/workspaces/{id}/deactivate-member")
    public void deactivate(@PathVariable Long id, @RequestBody AdminDtos.DeactivateRequest req, @Uid Long uid) {
        workspaceService.requireAdmin(id, uid);
        Membership m = memberships.findByWorkspaceIdAndUserId(id, req.userId())
                .orElseThrow(() -> Errors.notFound("Member not found"));
        m.setActive(false);
        memberships.save(m);
        auditService.recordAction(id, uid, "DEACTIVATE_MEMBER", String.valueOf(req.userId()));
    }

    @PostMapping("/workspaces/{id}/change-role")
    public void changeRole(@PathVariable Long id, @RequestBody AdminDtos.ChangeRoleRequest req, @Uid Long uid) {
        workspaceService.requireAdmin(id, uid);
        Membership m = memberships.findByWorkspaceIdAndUserId(id, req.userId())
                .orElseThrow(() -> Errors.notFound("Member not found"));
        m.setRole(req.role());
        memberships.save(m);
        auditService.recordAction(id, uid, "CHANGE_ROLE", String.valueOf(req.userId()));
    }

    @GetMapping("/workspaces/{id}/audit")
    public List<AuditLog> audit(@PathVariable Long id, @Uid Long uid) {
        workspaceService.requireAdmin(id, uid);
        return auditLogs.findByWorkspaceIdOrderByCreatedAtDesc(id);
    }

    @GetMapping("/workspaces/{id}/export")
    public Map<String, Object> export(@PathVariable Long id) {
        return workspaceExportService.export(id);
    }
}
