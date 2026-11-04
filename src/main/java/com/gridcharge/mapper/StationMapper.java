package com.gridcharge.mapper;

import com.gridcharge.entity.*;
import com.gridcharge.vo.*;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface StationMapper {

    Station findById(Long id);

    Station lockById(Long id);

    List<Station> page(
            @Param("offset") int offset, @Param("size") int size, @Param("name") String name);

    long count(@Param("name") String name);

    List<Station> all();

    List<Long> idsAfter(@Param("after") long after);

    void insert(Station station);

    void update(Station station);

    void delete(Long id);

    int countSlots(Long id);
}
