package hnist.communication.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import hnist.communication.exception.BusinessException;
import hnist.communication.entity.DictData;
import hnist.communication.entity.DictType;
import hnist.communication.mapper.DictDataMapper;
import hnist.communication.mapper.DictTypeMapper;
import hnist.communication.service.DictService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 字典类型与字典数据的管理。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DictServiceImpl implements DictService {

    private static final int MAX_PAGE_SIZE = 200;

    private final DictTypeMapper dictTypeMapper;
    private final DictDataMapper dictDataMapper;

    // ---- DictType ----
    @Override
    public Page<DictType> listTypes(int page, int size) {
        return dictTypeMapper.selectPage(new Page<>(Math.max(1, page), Math.min(MAX_PAGE_SIZE, Math.max(1, size))),
                new LambdaQueryWrapper<DictType>().orderByAsc(DictType::getId));
    }

    @Override
    public DictType getType(Long id) {
        return dictTypeMapper.selectById(id);
    }

    @Override
    @Transactional
    public DictType createType(DictType type) {
        if (dictTypeMapper.selectCount(new LambdaQueryWrapper<DictType>()
                .eq(DictType::getDictType, type.getDictType())) > 0) {
            throw new BusinessException("字典类型已存在");
        }
        DictType deleted = dictTypeMapper.selectByTypeIncludingDeleted(type.getDictType());
        if (deleted != null) {
            type.setId(deleted.getId());
            dictTypeMapper.restoreDeleted(type);
            return type;
        }
        dictTypeMapper.insert(type);
        return type;
    }

    /** 记录不存在时返回 null，不抛业务异常。 */
    @Override
    @Transactional
    public DictType updateType(Long id, DictType req) {
        DictType type = dictTypeMapper.selectById(id);
        if (type == null) return null;
        if (req.getDictType() != null) type.setDictType(req.getDictType());
        if (req.getDictName() != null) type.setDictName(req.getDictName());
        if (req.getStatus() != null) type.setStatus(req.getStatus());
        dictTypeMapper.updateById(type);
        return type;
    }

    @Override
    @Transactional
    public void deleteType(Long id) {
        dictTypeMapper.deleteById(id);
    }

    // ---- DictData ----
    @Override
    public List<DictData> listDataByType(String dictType) {
        // 按 dictSort 升序返回，保证选项顺序与配置的排序值一致。
        return dictDataMapper.selectList(
                new LambdaQueryWrapper<DictData>()
                        .eq(DictData::getDictType, dictType)
                        .orderByAsc(DictData::getDictSort));
    }

    @Override
    public DictData getData(Long id) {
        return dictDataMapper.selectById(id);
    }

    @Override
    @Transactional
    public DictData createData(DictData data) {
        dictDataMapper.insert(data);
        return data;
    }

    /** 记录不存在时返回 null，不抛业务异常。 */
    @Override
    @Transactional
    public DictData updateData(Long id, DictData req) {
        DictData data = dictDataMapper.selectById(id);
        if (data == null) return null;
        if (req.getDictType() != null) data.setDictType(req.getDictType());
        if (req.getDictLabel() != null) data.setDictLabel(req.getDictLabel());
        if (req.getDictValue() != null) data.setDictValue(req.getDictValue());
        if (req.getDictSort() != null) data.setDictSort(req.getDictSort());
        if (req.getCssClass() != null) data.setCssClass(req.getCssClass());
        if (req.getListClass() != null) data.setListClass(req.getListClass());
        if (req.getIsDefault() != null) data.setIsDefault(req.getIsDefault());
        if (req.getStatus() != null) data.setStatus(req.getStatus());
        dictDataMapper.updateById(data);
        return data;
    }

    @Override
    @Transactional
    public void deleteData(Long id) {
        dictDataMapper.deleteById(id);
    }
}
