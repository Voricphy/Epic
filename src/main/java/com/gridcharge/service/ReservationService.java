package com.gridcharge.service;

import com.gridcharge.dto.ReservationDTO;
import com.gridcharge.entity.Reservation;
import com.gridcharge.dto.PageResult;

public interface ReservationService {
    Reservation submit(Long userId, ReservationDTO reservationDTO, boolean synchronous);

    Reservation get(Long userId, Long id);

    PageResult<Reservation> list(Long userId, int page, int size);

    Reservation cancel(Long userId, Long id);

    void consume(Long id);

    void recover();
}
