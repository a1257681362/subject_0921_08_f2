package com.evops.staff;

import com.evops.common.ApiResponse;
import com.evops.common.VoidRequest;
import com.evops.staff.dto.LeaveRequest;
import com.evops.staff.dto.StaffSaveRequest;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/staff")
public class StaffController {
    private final StaffService staffService;

    public StaffController(StaffService staffService) {
        this.staffService = staffService;
    }

    @PostMapping
    public ApiResponse<Staff> create(@Validated @RequestBody StaffSaveRequest req) {
        return ApiResponse.ok(staffService.create(req));
    }

    @PutMapping("/{id}")
    public ApiResponse<Staff> update(@PathVariable Long id, @Validated @RequestBody StaffSaveRequest req) {
        return ApiResponse.ok(staffService.update(id, req));
    }

    @PostMapping("/{id}/void")
    public ApiResponse<Staff> voidStaff(@PathVariable Long id, @Validated @RequestBody VoidRequest req) {
        return ApiResponse.ok(staffService.voidStaff(id, req));
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<Staff> restore(@PathVariable Long id, @Validated @RequestBody VoidRequest req) {
        return ApiResponse.ok(staffService.restore(id, req));
    }

    @GetMapping
    public ApiResponse<List<Staff>> list(@RequestParam(required = false) String keyword,
                                         @RequestParam(defaultValue = "false") boolean includeVoided) {
        return ApiResponse.ok(staffService.list(keyword, includeVoided));
    }

    @GetMapping("/{id}")
    public ApiResponse<Staff> get(@PathVariable Long id) {
        return ApiResponse.ok(staffService.get(id));
    }

    @PostMapping("/{id}/leaves")
    public ApiResponse<StaffLeave> addLeave(@PathVariable Long id, @Validated @RequestBody LeaveRequest req) {
        return ApiResponse.ok(staffService.addLeave(id, req));
    }

    @GetMapping("/{id}/leaves")
    public ApiResponse<List<StaffLeave>> listLeaves(@PathVariable Long id) {
        return ApiResponse.ok(staffService.listLeaves(id));
    }

    @PostMapping("/leaves/{leaveId}/void")
    public ApiResponse<StaffLeave> voidLeave(@PathVariable Long leaveId, @Validated @RequestBody VoidRequest req) {
        return ApiResponse.ok(staffService.voidLeave(leaveId, req));
    }
}
