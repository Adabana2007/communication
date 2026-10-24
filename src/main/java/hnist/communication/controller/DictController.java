package hnist.communication.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import hnist.communication.common.Result;
import hnist.communication.entity.DictData;
import hnist.communication.entity.DictType;
import hnist.communication.service.DictService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 字典类型与字典数据的增删改查。 */
@RestController
@RequestMapping("/api/dicts")
@RequiredArgsConstructor
public class DictController {

    private final DictService dictService;

    // ---- DictType ----
    @GetMapping("/types")
    public Result<Page<DictType>> listTypes(@RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "10") int size) {
        return Result.success(dictService.listTypes(page, size));
    }

    @GetMapping("/types/{id}")
    public Result<DictType> getType(@PathVariable Long id) {
        return Result.success(dictService.getType(id));
    }

    @PostMapping("/types")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<DictType> createType(@RequestBody DictType type) {
        return Result.success(dictService.createType(type));
    }

    @PutMapping("/types/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<DictType> updateType(@PathVariable Long id, @RequestBody DictType type) {
        return Result.success(dictService.updateType(id, type));
    }

    @DeleteMapping("/types/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> deleteType(@PathVariable Long id) {
        dictService.deleteType(id);
        return Result.success();
    }

    // ---- DictData (by type) ----
    @GetMapping("/types/{type}/items")
    public Result<List<DictData>> listDataByType(@PathVariable String type) {
        return Result.success(dictService.listDataByType(type));
    }

    // ---- DictData CRUD ----
    @GetMapping("/items/{id}")
    public Result<DictData> getData(@PathVariable Long id) {
        return Result.success(dictService.getData(id));
    }

    @PostMapping("/items")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<DictData> createData(@RequestBody DictData data) {
        return Result.success(dictService.createData(data));
    }

    @PutMapping("/items/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<DictData> updateData(@PathVariable Long id, @RequestBody DictData data) {
        return Result.success(dictService.updateData(id, data));
    }

    @DeleteMapping("/items/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> deleteData(@PathVariable Long id) {
        dictService.deleteData(id);
        return Result.success();
    }
}
