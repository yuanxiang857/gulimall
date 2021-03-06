package com.yuanxiang.gulimall.seckill.service.imp;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.yuanxiang.common.to.mq.SeckillOrderTo;
import com.yuanxiang.common.utils.R;
import com.yuanxiang.common.vo.MemberRespVo;
import com.yuanxiang.gulimall.seckill.feign.CouponFeignService;
import com.yuanxiang.gulimall.seckill.feign.ProductFeignService;
import com.yuanxiang.gulimall.seckill.intercepter.LoginUserInterceptor;
import com.yuanxiang.gulimall.seckill.service.SeckillService;
import com.yuanxiang.gulimall.seckill.to.SecKillSkuRedisTo;
import com.yuanxiang.gulimall.seckill.vo.SeckillSessionsWithSkus;
import com.yuanxiang.gulimall.seckill.vo.SeckillSkuVo;
import com.yuanxiang.gulimall.seckill.vo.SkuInfoVo;
import jdk.nashorn.internal.ir.Block;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RSemaphore;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.BoundHashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class SeckillServiceImp implements SeckillService {

    @Autowired
    CouponFeignService couponFeignService;
    @Autowired
    StringRedisTemplate stringRedisTemplate;
    @Autowired
    ProductFeignService productFeignService;
    @Autowired
    RedissonClient redissonClient;
    @Autowired
    RabbitTemplate rabbitTemplate;

    private final String SESSION_CACHE_PREFIX = "seckill:sessions:";
    private final String SKUKILL_CACHE_PREFIX = "seckill:skus";
    private final String SKUKILL_STOCK_PREFIX = "seckill:stock:";

    public void getLatest3DaysSku() {
        //???????????????????????????????????????
        R latest3DaysSku = couponFeignService.getLatest3DaysSku();
        if (latest3DaysSku.getCode() == 0) {
            //????????????
            List<SeckillSessionsWithSkus> data = latest3DaysSku.getData(new TypeReference<List<SeckillSessionsWithSkus>>() {
            });
            //?????????redis
            //1?????????????????????
            saveSessionInfos(data);
            //2???????????????????????????????????????
            saveSessionSkuInfos(data);
        }
    }

    private void saveSessionSkuInfos(List<SeckillSessionsWithSkus> data) {
        if (data != null) {
            data.stream().forEach(session -> {
                //1?????????key
                long startTime = session.getStartTime().getTime();
                long endTime = session.getEndTime().getTime();
                String key = SESSION_CACHE_PREFIX + startTime + "_" + endTime;
                //2?????????value
                if (!stringRedisTemplate.hasKey(key)) {
                    List<String> s = session.getRelationSkus().stream().map(item -> item.getPromotionId() + "_" + item.getSkuId().toString()).collect(Collectors.toList());
                    stringRedisTemplate.opsForList().leftPushAll(key, s);
                }
            });
        }
    }

    private void saveSessionInfos(List<SeckillSessionsWithSkus> data) {
        if (data != null) {
            BoundHashOperations<String, Object, Object> operations = stringRedisTemplate.boundHashOps(SKUKILL_CACHE_PREFIX);
            data.stream().forEach(session -> {
                session.getRelationSkus().stream().forEach(item -> {
                    if (!operations.hasKey(item.getPromotionSessionId().toString() + "_" + item.getSkuId().toString())) {
                        SecKillSkuRedisTo redisTo = new SecKillSkuRedisTo();
                        //1???sku???????????????
                        R info = productFeignService.info(item.getSkuId());
//                if (info.getCode() == 0) {
                        SkuInfoVo data1 = info.getData("skuInfo", new TypeReference<SkuInfoVo>() {
                        });
                        redisTo.setSkuInfoVo(data1);
//                }
                        //2???sku???????????????
                        BeanUtils.copyProperties(item, redisTo);
                        //3?????????????????????????????????????????????
                        redisTo.setStartTime(session.getStartTime().getTime());
                        redisTo.setEndTime(session.getEndTime().getTime());
                        //4???????????? ???????????????????????????
                        String replace = UUID.randomUUID().toString().replace("-", "");
                        redisTo.setRandomCode(replace);
                        String s = JSON.toJSONString(redisTo);
                        operations.put(item.getPromotionSessionId().toString() + "_" + item.getSkuId().toString(), s);
                        //5?????????????????????-??????????????? ????????? ??????
                        RSemaphore semaphore = redissonClient.getSemaphore(SKUKILL_STOCK_PREFIX + replace);
                        //??????????????????????????????????????????.
                        semaphore.trySetPermits(item.getSeckillCount());
                    }
                });
            });
        }
    }

    public List<SecKillSkuRedisTo> blockHandler(BlockException e) {
        log.error("getCurrentSeckillSkuResource????????????");
        return null;
    }

    /**
     * blockHandler ??????????????????????????????/??????/?????????????????????????????????fallback????????????????????????????????????
     * @return
     */
    @SentinelResource(value = "getCurrentSeckillSkuResource",blockHandler = "blockHandler")
    @Override
    public List<SecKillSkuRedisTo> getCurrentSeckKillSkus() {
        //1???????????????????????????????????????
        Long time = new Date().getTime();
        try (Entry entry = SphU.entry("seckKillSkus")) {
            Set<String> keys = stringRedisTemplate.keys(SESSION_CACHE_PREFIX + "*");
            for (String key : keys) {
                String replace = key.replace(SESSION_CACHE_PREFIX, "");
                String[] s = replace.split("_");
                Long startTime = Long.parseLong(s[0]);
                Long endTime = Long.parseLong(s[1]);
                if (time >= startTime && time <= endTime) {
                    //2????????????????????????????????????????????????
                    List<String> range = stringRedisTemplate.opsForList().range(key, -100, 100);
                    BoundHashOperations<String, String, String> operations = stringRedisTemplate.boundHashOps(SKUKILL_CACHE_PREFIX);
                    List<String> objects = operations.multiGet(range);
                    if (objects != null) {
                        List<SecKillSkuRedisTo> collect = objects.stream().map(item -> {
                            SecKillSkuRedisTo redisTo = JSON.parseObject(item, SecKillSkuRedisTo.class);
                            return redisTo;
                        }).collect(Collectors.toList());
                        return collect;
                    }
                    break;
                }
            }
        } catch (BlockException e) {
            log.error("???????????????",e.getMessage());
        }
        //2?????????????????????????????????
        return null;
    }

    @Override
    public SecKillSkuRedisTo getSkuSeckillInfo(Long skuId) {
        BoundHashOperations<String, String, String> operations = stringRedisTemplate.boundHashOps(SKUKILL_CACHE_PREFIX);
        Set<String> keys = operations.keys();
        if (keys != null && keys.size() > 0) {
            String reg = "\\d_" + skuId;
            for (String key : keys) {
                if (Pattern.matches(reg, key)) {
                    String s = operations.get(key);
                    SecKillSkuRedisTo redisTo = JSON.parseObject(s, SecKillSkuRedisTo.class);
                    //?????????
                    Long time = new Date().getTime();
                    Long startTime = redisTo.getStartTime();
                    Long endTime = redisTo.getEndTime();
                    if (time >= startTime && time <= endTime) {
                    } else {
                        redisTo.setRandomCode(null);
                    }
                    return redisTo;
                }
            }
        }
        return null;
    }

    @Override
    public String kill(String killId, String key, Integer num) {
        long s1 = System.currentTimeMillis();
        MemberRespVo memberRespVo = LoginUserInterceptor.loginUser.get();
        //1???????????????????????????????????????
        BoundHashOperations<String, String, String> operations = stringRedisTemplate.boundHashOps(SKUKILL_CACHE_PREFIX);
        String s = operations.get(killId);
        if (StringUtils.isEmpty(s)) {
            return null;
        } else {
            SecKillSkuRedisTo redisTo = JSON.parseObject(s, SecKillSkuRedisTo.class);
            //2???????????????????????????
            Long time = new Date().getTime();
            Long startTime = redisTo.getStartTime();
            Long endTime = redisTo.getEndTime();
            Long ttl = endTime - time;//??????????????????
            if (time >= startTime && time <= endTime) {
                //3???????????????????????????id
                String randomCode = redisTo.getRandomCode();
                String skuId = redisTo.getPromotionId() + "_" + redisTo.getSkuId();
                if (randomCode.equals(key) && killId.equals(skuId)) {
                    //4?????????????????????????????????
                    if (num <= redisTo.getSeckillLimit()) {
                        String redisKey = memberRespVo.getId() + "_" + skuId;
                        //5????????????????????????????????????????????????????????????????????????????????????
                        Boolean aBoolean = stringRedisTemplate.opsForValue().setIfAbsent(redisKey, num.toString(), ttl, TimeUnit.MILLISECONDS);
                        if (aBoolean) {
                            //????????????
                            RSemaphore semaphore = redissonClient.getSemaphore(SKUKILL_STOCK_PREFIX + randomCode);
//                                semaphore.acquire(num);//??????
                            boolean b = semaphore.tryAcquire(num);
                            if (b) {
                                //??????????????????????????????????????????mq??????
                                String timeId = IdWorker.getTimeId();
                                SeckillOrderTo orderTo = new SeckillOrderTo();
                                //????????????
                                orderTo.setOrderSn(timeId);
                                orderTo.setSkuId(redisTo.getSkuId());
                                orderTo.setNum(num);
                                orderTo.setPromotionSessionId(redisTo.getPromotionSessionId());
                                orderTo.setMemberId(memberRespVo.getId());
                                orderTo.setSeckillPrice(redisTo.getSeckillPrice());
                                rabbitTemplate.convertAndSend("order-event-exchange", "order.seckill.order", orderTo);
                                long s2 = System.currentTimeMillis();
                                log.info("??????=", (s2 - s1));
                                return timeId;
                            }
                        } else {
                            return null;
                        }
                    }

                } else {
                    return null;
                }
            } else {
                return null;
            }
            return null;
        }
    }

}
