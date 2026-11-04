package com.gridcharge.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.gridcharge.dto.*;
import com.gridcharge.entity.*;
import com.gridcharge.exception.BusinessException;
import com.gridcharge.mapper.*;
import com.gridcharge.dto.PageResult;
import com.gridcharge.service.*;
import com.gridcharge.utils.CapacityPolicy;
import com.gridcharge.utils.RedisData;
import com.gridcharge.utils.RedisKeyConstant;
import com.gridcharge.vo.*;
import jakarta.annotation.PreDestroy;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class StationServiceImpl implements StationService {
    private static final Logger log = LoggerFactory.getLogger(StationServiceImpl.class);
    // 练习阶段设为 30 秒，便于观察逻辑过期和重建。
    private static final long LOGICAL_EXPIRE_SECONDS = 30;
    private static final long NULL_CACHE_SECONDS = 180;
    private final ThreadPoolExecutor cacheRebuildExecutor = new ThreadPoolExecutor(
            2, 4, 60, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(100), new ThreadPoolExecutor.AbortPolicy());

    @PreDestroy
    public void shutdownCacheRebuildExecutor() {
        cacheRebuildExecutor.shutdown();
    }

    @Autowired
    private StationMapper stationMapper;
    @Autowired
    private SlotMapper slotMapper;

    @Autowired
    private ReservationMapper reservationMapper;

    @Autowired
    private AdminTransactionLock adminTransactionLock;

    @Autowired
    private AdminCancellationMailMapper cancellationMailMapper;

    @Autowired
    private StationBloomFilter stationBloomFilter;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private RedissonClient redissonClient;

    @Override
    public Station detail(Long id) {
//        return queryWithPassThrough(id);
//          return queryWithMutex(id);
//        return queryWithRandomTtl(id);
        return queryWithLogicalExpire(id);
    }

    /** 缓存穿透练习：缓存空值，保留完整实现供对照。 */
    private Station queryWithPassThrough(Long id) {
        String key = RedisKeyConstant.station(id);
//      先查Redis
        String stationJson = stringRedisTemplate.opsForValue().get(key);
//      判断是否命中
//      命中直接返回(JSON-->Station)
        if(StrUtil.isNotBlank(stationJson)){
            Station station = JSONUtil.toBean(stationJson, Station.class);
            return station;
        }
        if(stationJson != null){
            throw new BusinessException(404, "充电站不存在！");
        }
//      未命中再查数据库
        Station station = stationMapper.findById(id);
//      判断数据库中是否存在站点
//      不存在充电站则抛出异常
        if(station == null){
            stringRedisTemplate.opsForValue().set(key, "", 3, TimeUnit.MINUTES);
            throw new  BusinessException(404, "充电站不存在！");
        }
//      存在则写入Redis(Station-->JSON)并设置TTL
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(station), 30, TimeUnit.MINUTES);
        return station;
    }

    /** 缓存击穿练习：使用 Redisson 互斥锁重建缓存。 */
    private Station queryWithMutex(Long id) {
        String stationKey = RedisKeyConstant.station(id);
//        先查缓存
        String stationJson = stringRedisTemplate.opsForValue().get(stationKey);
//        命中直接返回结果，未命中则尝试获取互斥锁
        if(StrUtil.isNotBlank(stationJson)){
            return JSONUtil.toBean(stationJson, Station.class);
        }
        if(stationJson != null){
            throw new BusinessException(404, "充电站不存在！");
        }
        RLock lock = redissonClient.getLock("gc:lock:station:"+id);
        boolean acquiredFlag = false;
        try {
            acquiredFlag = lock.tryLock(2, TimeUnit.SECONDS);
            if(!acquiredFlag){
                throw new BusinessException(503, "查询繁忙，请稍后重试！");
            }
            stationJson = stringRedisTemplate.opsForValue().get(stationKey);
            if(StrUtil.isNotBlank(stationJson)){
                return JSONUtil.toBean(stationJson, Station.class);
            }
            if(stationJson != null){
                throw new BusinessException(404, "充电站不存在！");
            }
            Station station = stationMapper.findById(id);
            if(station == null){
                stringRedisTemplate.opsForValue().set(stationKey, "", 3, TimeUnit.MINUTES);
                throw new BusinessException(404, "充电站不存在！");
            }
            stringRedisTemplate.opsForValue().set(stationKey, JSONUtil.toJsonStr(station), 30, TimeUnit.MINUTES);
            return station;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(503, "查询被中断，请稍后重试！");
        } finally {
            if(acquiredFlag && lock.isHeldByCurrentThread()){
                lock.unlock();
            }
        }
    }

    /** 缓存雪崩练习：给 TTL 添加随机偏移，错开大量缓存的过期时间。 */
    private Station queryWithRandomTtl(Long id) {
        String stationKey = RedisKeyConstant.station(id);
//        先查缓存，命中直接返回
        String stationJson = stringRedisTemplate.opsForValue().get(stationKey);
        if(StrUtil.isNotBlank(stationJson)){
            return JSONUtil.toBean(stationJson, Station.class);
        }
        if(stationJson != null){
            throw new BusinessException(404, "充电站不存在！");
        }
//        未命中再查数据库
        Station station = stationMapper.findById(id);
        if(station == null){
//            空值缓存：3 分钟加 0～60 秒随机偏移，避免空值也集中失效
            long ttl = TimeUnit.MINUTES.toSeconds(3) + ThreadLocalRandom.current().nextLong(61);
            stringRedisTemplate.opsForValue().set(stationKey, "", ttl, TimeUnit.SECONDS);
            throw new BusinessException(404, "充电站不存在！");
        }
//        站点缓存：30 分钟加 0～300 秒随机偏移，每次写入时重新生成
        long ttl = TimeUnit.MINUTES.toSeconds(30) + ThreadLocalRandom.current().nextLong(301);
        stringRedisTemplate.opsForValue().set(stationKey, JSONUtil.toJsonStr(station), ttl, TimeUnit.SECONDS);
        return station;
    }

    /** 未命中时加锁回源，命中过期数据则返回旧值并异步重建。 */
    private Station queryWithLogicalExpire(Long id) {
        String stationKey = RedisKeyConstant.station(id);
//        先查缓存
        String redisDataJson = stringRedisTemplate.opsForValue().get(stationKey);
        if (redisDataJson == null) {
            if (!stationBloomFilter.mightContain(id))
                throw new BusinessException(404, "充电站不存在");
            return loadMissingStation(id);
        }
        if (redisDataJson.isEmpty()) throw new BusinessException(404, "充电站不存在");
//        解析缓存数据
        RedisData redisData = JSONUtil.toBean(redisDataJson, RedisData.class);
        Station station = JSONUtil.toBean(JSONUtil.toJsonStr(redisData.getData()), Station.class);
//        检查逻辑过期时间，未过期直接返回
        if(redisData.getExpireTime().isAfter(LocalDateTime.now())){
            return station;
        }

        try {
            cacheRebuildExecutor.submit(() -> {
                RLock lock = redissonClient.getLock("gc:lock:station:logical:" + id);
                if (!lock.tryLock()) return;
                try {
                    // 拿到锁后重新查缓存，已有其他线程重建则直接结束。
                    String latestJson = stringRedisTemplate.opsForValue().get(stationKey);
                    if (StrUtil.isNotBlank(latestJson)) {
                        RedisData latestData = JSONUtil.toBean(latestJson, RedisData.class);
                        if (latestData.getExpireTime().isAfter(LocalDateTime.now())) return;
                        Station newStation = stationMapper.findById(id);
                        if (newStation == null) {
                            cacheMissingStation(stationKey);
                        } else {
                            setWithLogicalExpire(stationKey, newStation,
                                    LOGICAL_EXPIRE_SECONDS, TimeUnit.SECONDS);
                        }
                    }
                } catch (Exception e) {
                    log.warn("站点缓存重建失败，站点 ID：{}", id, e);
                } finally {
                    if (lock.isHeldByCurrentThread()) lock.unlock();
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("缓存重建队列已满，暂时返回旧值，站点 ID：{}", id);
        }

        return station;
    }

    private Station loadMissingStation(Long id) {
        RLock lock = redissonClient.getLock("gc:lock:station:logical:" + id);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(2, TimeUnit.SECONDS);
            if (!acquired) throw new BusinessException(503, "查询繁忙，请稍后重试");
            String cached = stringRedisTemplate.opsForValue().get(RedisKeyConstant.station(id));
            if (StrUtil.isNotBlank(cached)) {
                RedisData data = JSONUtil.toBean(cached, RedisData.class);
                return JSONUtil.toBean(JSONUtil.toJsonStr(data.getData()), Station.class);
            }
            if (cached != null) throw new BusinessException(404, "充电站不存在");
            Station station = stationMapper.findById(id);
            if (station == null) {
                cacheMissingStation(RedisKeyConstant.station(id));
                throw new BusinessException(404, "充电站不存在");
            }
            setWithLogicalExpire(RedisKeyConstant.station(id), station,
                    LOGICAL_EXPIRE_SECONDS, TimeUnit.SECONDS);
            return station;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(503, "查询被中断，请稍后重试");
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

    private void cacheMissingStation(String key) {
        long ttl = NULL_CACHE_SECONDS + ThreadLocalRandom.current().nextLong(61);
        stringRedisTemplate.opsForValue().set(key, "", ttl, TimeUnit.SECONDS);
    }

    @Override
    public Station adminDetail(Long id) {
        Station station = stationMapper.findById(id);
        if (station == null) throw new BusinessException(404, "充电站不存在");
        return station;
    }

    private void invalidateAfterCommit(Long id) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // 和回源/重建使用同一把锁，防止旧数据在删除缓存后重新写入。
                try {
                    RLock lock = redissonClient.getLock("gc:lock:station:logical:" + id);
                    lock.lock();
                    try {
                        stringRedisTemplate.delete(RedisKeyConstant.station(id));
                    } finally {
                        if (lock.isHeldByCurrentThread()) lock.unlock();
                    }
                } catch (RuntimeException e) {
                    // 数据库已提交，不把缓存失败误报为数据库写入失败。
                    // 现有缓存逻辑过期后仍可重建。
                    log.error("站点变更已提交，但缓存失效失败，站点 ID：{}", id, e);
                }
            }
        });
    }

    /** 负责将逻辑过期时间写入缓存 */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    @Override
    public PageResult<Station> page(int page, int size, String name) {
        return new PageResult<>(stationMapper.count(name), stationMapper.page((page - 1) * size, size, name));
    }

    @Transactional
    @Override
    public Long save(Long id, StationDTO dto) {
        Station station = new Station();
        BeanUtils.copyProperties(dto, station);
        station.setId(id);
        if (id == null) {
            stationBloomFilter.beforeCreate();
            stationMapper.insert(station);
            stationBloomFilter.add(station.getId());
        } else {
            adminTransactionLock.station(id);
            if (stationMapper.findById(id) == null) throw new BusinessException(404, "充电站不存在");
            if (slotMapper.capacityViolations(id, dto.getPileCount(), dto.getMaxPowerKw()) > 0)
                throw new BusinessException("新容量无法承载现有预约时段");
            stationMapper.update(station);
        }
        invalidateAfterCommit(station.getId());
        return station.getId();
    }

    @Transactional
    @Override
    public void delete(Long id) {
        adminTransactionLock.station(id);
        if (stationMapper.findById(id) == null) throw new BusinessException(404, "充电站不存在");
        if (stationMapper.countSlots(id) > 0) throw new BusinessException("存在预约时段的站点不能删除");
        stationMapper.delete(id);
        invalidateAfterCommit(id);
    }

    @Override
    public List<NearbyStationVO> nearby(
            double longitude, double latitude, double radius, int limit) {
        // TODO 学习任务：自行完成 StationService.nearby。
        throw new UnsupportedOperationException("待实现：StationService.nearby");
    }

    @Override
    public List<StationSlot> slots(Long stationId) {
        detail(stationId);
        return slotMapper.list(stationId);
    }

    @Transactional
    @Override
    public Long publish(Long stationId, SlotDTO dto) {
        if (!dto.getStartTime().isAfter(LocalDateTime.now())
                || !dto.getEndTime().isAfter(dto.getStartTime()))
            throw new BusinessException(400, "预约时段时间范围不合法");
        adminTransactionLock.station(stationId);
        Station station = stationMapper.findById(stationId);
        if (station == null) throw new BusinessException(404, "充电站不存在");
        if (!Integer.valueOf(1).equals(station.getStatus())) throw new BusinessException("站点已停用");
        CapacityPolicy.validate(station, dto.getPowerPerCarKw(), dto.getQuota());
        if (slotMapper.overlaps(stationId, dto.getStartTime(), dto.getEndTime()) > 0)
            throw new BusinessException("同站点时段不能重叠");
        StationSlot slot = new StationSlot();
        BeanUtils.copyProperties(dto, slot);
        slot.setStationId(stationId);
        slotMapper.insert(slot);
        // TODO 学习任务：发布成功后的关注动态推送。
        return slot.getId();
    }

    @Transactional
    @Override
    public void closeSlot(Long slotId) {
        lockSlotForManagement(slotId);
        slotMapper.close(slotId);
    }

    @Transactional
    @Override
    public void openSlot(Long slotId) {
        StationSlot slot = lockSlotForManagement(slotId);
        Station station = stationMapper.findById(slot.getStationId());
        if (station == null) throw new BusinessException(404, "充电站不存在");
        if (!Integer.valueOf(1).equals(station.getStatus())) throw new BusinessException("站点已停用");
        if (!slot.getStartTime().isAfter(LocalDateTime.now()))
            throw new BusinessException("时段已开始或已结束，不能重新开放，请发布新时段");
        // 重复开放直接成功；不修改订单、已占用名额或取消通知。
        if (Integer.valueOf(1).equals(slot.getStatus())) return;
        if (slotMapper.open(slotId) != 1)
            throw new BusinessException("时段状态或开始时间已不允许重新开放");
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public int cancelSlot(Long slotId) {
        lockSlotForManagement(slotId);
        slotMapper.close(slotId);
        // 仅落库，不在事务内发邮件；事务提交后后台任务才能读取到这些通知。
        int notifications = cancellationMailMapper.enqueueActive(slotId);
        // 提交、消费和取消都先锁时段，再修改订单，防止漏单及相反锁序造成死锁。
        int cancelled = reservationMapper.cancelActiveBySlot(slotId);
        if (notifications != cancelled) {
            throw new IllegalStateException("取消订单与邮件通知任务数量不一致");
        }
        // 所有成功订单已取消；PROCESSING 本来不占名额，不逐单减库存。
        slotMapper.clearReserved(slotId);
        return cancelled;
    }

    private StationSlot lockSlotForManagement(Long slotId) {
        StationSlot slot = slotMapper.findById(slotId);
        if (slot == null) throw new BusinessException(404, "时段不存在");
        adminTransactionLock.station(slot.getStationId());
        adminTransactionLock.slot(slotId);
        // 用户端仍使用 MySQL 时段锁，保留这个锁定读与预约提交、消费互斥。
        StationSlot lockedSlot = slotMapper.lockById(slotId);
        if (lockedSlot == null) throw new BusinessException(404, "时段不存在");
        return lockedSlot;
    }

    @Override
    public void rebuildGeo() {
        // TODO 学习任务：自行完成 StationService.rebuildGeo。
        throw new UnsupportedOperationException("待实现：StationService.rebuildGeo");
    }
}
