package hnist.communication.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import hnist.communication.entity.DictData;
import hnist.communication.entity.DictType;

import java.util.List;

/** 数据字典类型与字典项的维护。 */
public interface DictService {

    // ---- DictType ----
    Page<DictType> listTypes(int page, int size);

    DictType getType(Long id);

    DictType createType(DictType type);

    DictType updateType(Long id, DictType type);

    void deleteType(Long id);

    // ---- DictData ----
    List<DictData> listDataByType(String dictType);

    DictData getData(Long id);

    DictData createData(DictData data);

    DictData updateData(Long id, DictData data);

    void deleteData(Long id);
}
