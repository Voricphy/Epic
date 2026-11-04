package com.gridcharge.service.impl;

import com.gridcharge.dto.*;
import com.gridcharge.entity.*;
import com.gridcharge.exception.BusinessException;
import com.gridcharge.mapper.*;
import com.gridcharge.dto.PageResult;
import com.gridcharge.service.*;
import com.gridcharge.utils.SnowflakeIdWorker;
import com.gridcharge.vo.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 学习骨架：保留 MySQL 基础操作，TODO 方法由学习者补充。 */
@Service
@RequiredArgsConstructor
public class ReservationServiceImpl implements ReservationService {
    @Autowired
    private  ReservationMapper reservationMapper;
    @Autowired
    private SlotMapper slotMapper;
    @Autowired
    private SnowflakeIdWorker snowflakeIdWorker;

    @Override
    @Transactional
    public Reservation submit(Long userId, ReservationDTO reservationDTO, boolean synchronous) {
//        查询充电时段
        Long slotId = reservationDTO.getSlotId();
        String requestId = reservationDTO.getRequestId();
//        同一次请求重试，返回之前的订单
        Reservation oldOrder = reservationMapper.findRequest(userId, requestId);
        if(oldOrder != null){
            if(!Objects.equals(oldOrder.getSlotId(), slotId)){
                throw new BusinessException("同一个请求标识不能用于预约不同时段！");
            }
            return oldOrder;
        }
//        新请求：检查该用户是否已经预约了该时段（一人一时段一单）
        Reservation activeOrder = reservationMapper.activeForUser(userId, slotId);
        if(activeOrder != null){
            throw new BusinessException("您已预约该时段，请勿重复预约！");
        }


        // 持有时段锁直到订单提交，管理员取消时不会漏掉并发插入的 PROCESSING 订单。
        StationSlot stationSlot = slotMapper.lockById(slotId);
        if(stationSlot == null){
            throw new BusinessException(404, "预约时段不存在！");
        }
        if (!Integer.valueOf(1).equals(stationSlot.getStatus())) {
            throw new BusinessException("该时段未开放预约！");
        }
        if (!stationSlot.getStartTime().isAfter(LocalDateTime.now())) {
            throw new BusinessException("该时段已经开始，不能预约！");
        }
        if (stationSlot.getReserved() >= stationSlot.getQuota()) {
            throw new BusinessException("该时段名额已满！");
        }
//        生成订单ID
        long id = snowflakeIdWorker.nextId();
//        创建订单
        Reservation reservation = new Reservation();
        reservation.setId(id);
        reservation.setUserId(userId);
        reservation.setSlotId(slotId);
        reservation.setRequestId(reservationDTO.getRequestId());

        reservationMapper.insert(reservation);
        if (synchronous) {
            // 同步模式在当前事务中完成预约；异步模式交给消费者扣减名额。
            int rows = slotMapper.reserve(slotId);
            if (rows != 1) {
                throw new BusinessException("名额不足或时段已不可预约！");
            }
            int changed = reservationMapper.transition(id, "PROCESSING", "SUCCESS", null);
            if (changed != 1) {
                throw new BusinessException("预约订单状态更新失败");
            }
        }
        // 异步模式返回 PROCESSING，仅代表已受理，不代表已获得名额。
        return reservationMapper.findById(id);
    }

    @Override
    public Reservation get(Long userId, Long id) {
        Reservation reservation = reservationMapper.findById(id);
        if (reservation == null || !reservation.getUserId().equals(userId))
            throw new BusinessException(404, "预约不存在");
        return reservation;
    }

    @Override
    public PageResult<Reservation> list(Long userId, int page, int size) {
        return new PageResult<>(reservationMapper.count(userId), reservationMapper.list(userId, (page - 1) * size, size));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Reservation cancel(Long userId, Long id) {
        Reservation existing = reservationMapper.findById(id);
        if (existing == null || !Objects.equals(existing.getUserId(), userId))
            throw new BusinessException(404, "预约不存在");
        // 首次读取只用于校验归属和定位时段；与消费者、管理员取消统一锁顺序。
        StationSlot slot = slotMapper.lockById(existing.getSlotId());
        if (slot == null) throw new BusinessException(404, "预约时段不存在");
        Reservation reservation = reservationMapper.lockById(id);
        if (reservation == null || !Objects.equals(reservation.getUserId(), userId))
            throw new BusinessException(404, "预约不存在");
        String status = reservation.getStatus();
        // 重试不受时段开始时间影响，也保留管理员取消时记录的原因。
        if ("CANCELLED".equals(status)) return reservation;
        if (!"SUCCESS".equals(status) && !"PROCESSING".equals(status))
            throw new BusinessException("当前预约状态不支持取消");
        if (!slot.getStartTime().isAfter(LocalDateTime.now()))
            throw new BusinessException("预约时段已开始，不能取消");

        String reason = "用户主动取消预约";
        if (reservationMapper.transition(id, status, "CANCELLED", reason) != 1)
            throw new IllegalStateException("取消预约状态更新失败，订单 ID：" + id);
        // PROCESSING 尚未占用名额；仅成功订单释放一个名额，失败则回滚订单变更。
        if ("SUCCESS".equals(status) && slotMapper.release(slot.getId()) != 1)
            throw new IllegalStateException("取消预约名额释放失败，订单 ID：" + id);
        reservation.setStatus("CANCELLED");
        reservation.setFailureReason(reason);
        return reservation;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void consume(Long id) {
        Reservation existing = reservationMapper.findById(id);
        if (existing == null) {
            // 不把尚未查到的订单当作消费成功，让异常交给 RocketMQ 重试。
            throw new IllegalStateException("预约订单不存在，订单 ID：" + id);
        }
        // 只用首次读取定位时段；等待时段锁后，必须通过锁定读重新检查订单状态。
        // 与管理员取消统一为 时段 -> 订单 的锁顺序。
        if (slotMapper.lockById(existing.getSlotId()) == null) {
            throw new IllegalStateException("预约时段不存在，订单 ID：" + id);
        }
        Reservation reservation = reservationMapper.lockById(id);
        if (reservation == null) throw new IllegalStateException("预约订单不存在，订单 ID：" + id);
        if (!"PROCESSING".equals(reservation.getStatus())) {
            // 成功、失败或已取消的订单不再扣减名额。
            return;
        }

        // SQL 同时检查剩余名额、时段开放状态和开始时间，避免并发超卖。
        int rows = slotMapper.reserve(reservation.getSlotId());
        if (rows != 0 && rows != 1) {
            throw new IllegalStateException("预约名额更新行数异常，订单 ID：" + id);
        }
        String status = rows == 1 ? "SUCCESS" : "FAILED";
        String reason = rows == 1 ? null : "名额不足或时段已不可预约";
        int changed = reservationMapper.transition(id, "PROCESSING", status, reason);
        if (changed != 1) {
            // 更新订单失败时，事务也会回滚刚才的名额扣减。
            throw new IllegalStateException("预约订单状态更新失败，订单 ID：" + id);
        }
        // 业务失败正常提交 FAILED；数据库异常向外抛出，由事务回滚并触发消息重试。
    }

    @Override
    public void recover() {
        // TODO 学习任务：自行完成 ReservationService.recover。
        throw new UnsupportedOperationException("待实现：ReservationService.recover");
    }
}
