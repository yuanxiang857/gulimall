package com.yuanxiang.gulimall.order.service.impl;

import com.alibaba.fastjson.TypeReference;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.yuanxiang.common.exception.NoStockException;
import com.yuanxiang.common.to.mq.OrderTo;
import com.yuanxiang.common.to.mq.SeckillOrderTo;
import com.yuanxiang.common.utils.R;
import com.yuanxiang.common.vo.MemberRespVo;
import com.yuanxiang.gulimall.order.constant.OrderConstant;
import com.yuanxiang.gulimall.order.entity.OrderItemEntity;
import com.yuanxiang.gulimall.order.entity.PaymentInfoEntity;
import com.yuanxiang.gulimall.order.enume.OrderStatusEnum;
import com.yuanxiang.gulimall.order.feign.CartFeignService;
import com.yuanxiang.gulimall.order.feign.MemberFeignService;
import com.yuanxiang.gulimall.order.feign.ProductFeignService;
import com.yuanxiang.gulimall.order.feign.WmsFeignService;
import com.yuanxiang.gulimall.order.interceptor.LoginUserInterceptor;
import com.yuanxiang.gulimall.order.service.OrderItemService;
import com.yuanxiang.gulimall.order.service.PaymentInfoService;
import com.yuanxiang.gulimall.order.to.OrderCreateTo;
import com.yuanxiang.gulimall.order.vo.*;
//import io.seata.spring.annotation.GlobalTransactional;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yuanxiang.common.utils.PageUtils;
import com.yuanxiang.common.utils.Query;

import com.yuanxiang.gulimall.order.dao.OrderDao;
import com.yuanxiang.gulimall.order.entity.OrderEntity;
import com.yuanxiang.gulimall.order.service.OrderService;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;


@Service("orderService")
public class OrderServiceImpl extends ServiceImpl<OrderDao, OrderEntity> implements OrderService {

    private ThreadLocal<OrderSubmitVo> confirmVoThreadLocal = new ThreadLocal<>();

    @Autowired
    OrderItemService orderItemService;
    //    @Autowired
//    OrderDao orderDao;
//    @Autowired
//    OrderItemDao orderItemDao;
    @Autowired
    CartFeignService cartFeignService;
    @Autowired
    MemberFeignService memberFeignService;
    @Autowired
    ThreadPoolExecutor executor;
    @Autowired
    WmsFeignService wmsFeignService;
    @Autowired
    StringRedisTemplate redisTemplate;
    @Autowired
    ProductFeignService productFeignService;
    @Autowired
    RabbitTemplate rabbitTemplate;

    @Autowired
    PaymentInfoService paymentInfoService;

    @Override
    public PageUtils queryPage(Map<String, Object> params) {
        IPage<OrderEntity> page = this.page(
                new Query<OrderEntity>().getPage(params),
                new QueryWrapper<OrderEntity>()
        );
        return new PageUtils(page);
    }

    @Override
    public OrderConfirmVo confirmOrder() throws ExecutionException, InterruptedException {
        OrderConfirmVo orderConfirmVo = new OrderConfirmVo();
        MemberRespVo memberRespVo = LoginUserInterceptor.loginUser.get();//?????????????????????????????????
        System.out.println("?????????" + Thread.currentThread().getId());

        RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();

        CompletableFuture<Void> addressFuture = CompletableFuture.runAsync(() -> {
            System.out.println("??????addr" + Thread.currentThread().getId());
            RequestContextHolder.setRequestAttributes(requestAttributes);
            //1??????????????????????????????????????????
            List<MemberAddressVo> address = memberFeignService.getAddress(memberRespVo.getId());//????????????ID
            orderConfirmVo.setAddress(address);
        }, executor);
        CompletableFuture<Void> cartFuture = CompletableFuture.runAsync(() -> {
            RequestContextHolder.setRequestAttributes(requestAttributes);
            System.out.println("??????cart" + Thread.currentThread().getId());
            //2????????????????????????????????????????????????
            List<OrderItemVo> currentUserCartItems = cartFeignService.getCurrentUserCartItems();//????????? ???????????????????????????
            orderConfirmVo.setItems(currentUserCartItems);
            //Feign???????????????????????????????????????????????????????????????
        }, executor).thenRunAsync(() -> {
            List<OrderItemVo> items = orderConfirmVo.getItems();
            List<Long> collect = items.stream().map(item -> item.getSkuId()).collect(Collectors.toList());
            R skuHasStock = wmsFeignService.getSkuHasStock(collect);
            List<SkuStockVo> data = skuHasStock.getData(new TypeReference<List<SkuStockVo>>() {
            });
            if (data != null) {
                Map<Long, Boolean> map = data.stream().collect(Collectors.toMap(SkuStockVo::getSkuId, SkuStockVo::getHasStock));
                orderConfirmVo.setStocks(map);
            }
        }, executor);
        //3?????????????????????
        Integer integration = memberRespVo.getIntegration();
        orderConfirmVo.setIntegration(integration);
        //4???????????????????????????
        //TODO 5???????????????
        String token = UUID.randomUUID().toString().replace("-", "");
        orderConfirmVo.setOrderToken(token);
        redisTemplate.opsForValue().set(OrderConstant.USER_ORDER_TOKEN_PREFIX + memberRespVo.getId(), token, 30, TimeUnit.MINUTES);

        CompletableFuture.allOf(addressFuture, cartFuture).get();
        return orderConfirmVo;
    }

    //?????????????????????????????????????????????????????????????????????????????????????????????????????????
    //?????????????????????????????????????????????+??????????????????
    //??????????????????2PC???TCC
//    @GlobalTransactional
    @Transactional
    @Override
    public SubmitOrderResponseVo submitOrder(OrderSubmitVo vo) {
        confirmVoThreadLocal.set(vo);
        //???????????????????????????
        MemberRespVo memberRespVo = LoginUserInterceptor.loginUser.get();
        //???????????????????????????????????????,?????????????????????
        //1???????????????
        SubmitOrderResponseVo responseVo = new SubmitOrderResponseVo();
        responseVo.setCode(0);
        String orderToken = vo.getOrderToken();
        //????????????????????????????????? 0?????????????????? 1????????????
//        String script = "if redis.call('get',KEYS[1]) == ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end";
        String script = "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";
        Long result = redisTemplate.execute(new DefaultRedisScript<Long>(script, Long.class), Arrays.asList(OrderConstant.USER_ORDER_TOKEN_PREFIX + memberRespVo.getId()), orderToken);
        if (result == 0L) {
            //??????
            responseVo.setCode(1);
            return responseVo;
        } else {
            OrderCreateTo createTo = creatOrder();
            //??????
            BigDecimal payAmount = createTo.getOrderEntity().getPayAmount();
            BigDecimal payPrice = vo.getPayPrice();
            if (Math.abs(payAmount.subtract(payPrice).doubleValue()) < 0.1) {
                //3???????????????
                saveOrder(createTo);
                //4???????????????,???????????????  ??????????????????
                WareSkuLockVo wareSkuLockVo = new WareSkuLockVo();
                wareSkuLockVo.setOrderSn(createTo.getOrderEntity().getOrderSn());
                List<OrderItemVo> collect = createTo.getOrderItems().stream().map(item -> {
                    OrderItemVo orderItemVo = new OrderItemVo();
                    orderItemVo.setSkuId(item.getSkuId());
                    orderItemVo.setCount(item.getSkuQuantity());
                    orderItemVo.setTitle(item.getSkuName());
                    return orderItemVo;
                }).collect(Collectors.toList());
                wareSkuLockVo.setLocks(collect);
                //TODO ???????????????
                R r = wmsFeignService.orderLockStock(wareSkuLockVo);
                if (r.getCode() == 0) {
                    //??????
                    responseVo.setOrder(createTo.getOrderEntity());
                    //???????????????????????????
                    rabbitTemplate.convertAndSend("order-event-exchange", "order.create.order", createTo.getOrderEntity());
                    System.out.println("????????????");
                    //TODO ???????????????????????????
                    return responseVo;
                } else {
                    //??????
                    String msg = (String) r.get("msg");
                    throw new NoStockException(msg);
                }
            } else {
                responseVo.setCode(2);
                return responseVo;
            }
        }
    }
    @Override
    public OrderEntity getOrderByOrderSn(String orderSn) {
        OrderEntity order_sn = this.getOne(new QueryWrapper<OrderEntity>().eq("order_sn", orderSn));
        return order_sn;
    }

    @Override
    public void closeOrder(OrderEntity orderEntity) {
        //?????????????????????????????????
        OrderEntity byId = this.getById(orderEntity.getId());
        if (byId.getStatus() == OrderStatusEnum.CREATE_NEW.getCode()) {
            OrderEntity update = new OrderEntity();
            update.setId(orderEntity.getId());
            update.setStatus(OrderStatusEnum.CANCLED.getCode());
            this.updateById(update);
            OrderTo orderTo = new OrderTo();
            BeanUtils.copyProperties(orderEntity, orderTo);
            try {
                //TODO ???????????????????????????,,??????????????????????????????
                rabbitTemplate.convertAndSend("order-event-exchange", "order.release.other", orderTo);
            } catch (Exception e) {

            }
        }
    }

    @Override
    public PayVo getOrderPay(String orderSn) {
        PayVo payVo=new PayVo();
        OrderEntity orderEntity = this.getOrderByOrderSn(orderSn);
        BigDecimal amount = orderEntity.getPayAmount().setScale(2, BigDecimal.ROUND_UP);
        payVo.setTotal_amount(amount.toString());
        payVo.setOut_trade_no(orderSn);
        List<OrderItemEntity> order_sn = orderItemService.list(new QueryWrapper<OrderItemEntity>().eq("order_sn", orderSn));
        OrderItemEntity orderItemEntity = order_sn.get(0);
        payVo.setSubject(orderItemEntity.getSkuName());
        payVo.setBody(orderItemEntity.getSkuAttrsVals());
        return payVo;
    }

    @Override
    public PageUtils queryPageWithItems(Map<String, Object> params) {
        MemberRespVo memberRespVo = LoginUserInterceptor.loginUser.get();
        IPage<OrderEntity> page = this.page(
                new Query<OrderEntity>().getPage(params),
                new QueryWrapper<OrderEntity>().eq("member_id", memberRespVo.getId()).orderByDesc("id")
        );
        List<OrderEntity> order_sn1 = page.getRecords().stream().map(order -> {
            List<OrderItemEntity> order_sn = orderItemService.list(new QueryWrapper<OrderItemEntity>().eq("order_sn", order.getOrderSn()));
            order.setItemEntities(order_sn);
            return order;
        }).collect(Collectors.toList());
        page.setRecords(order_sn1);
        return new PageUtils(page);
    }

    /*
    ??????????????????????????????
     */
    @Override
    public String handlePayResult(PayAsyncVo vo) {
        //1?????????????????????
        PaymentInfoEntity paymentInfoEntity = new PaymentInfoEntity();
        paymentInfoEntity.setOrderSn(vo.getOut_trade_no());
        paymentInfoEntity.setAlipayTradeNo(vo.getTrade_no());
        paymentInfoEntity.setPaymentStatus(vo.getTrade_status());
        paymentInfoEntity.setCallbackTime(vo.getNotify_time());
        paymentInfoService.save(paymentInfoEntity);
        //2??????????????????????????????
        if(vo.getTrade_status().equals("TRADE_SUCCESS") || vo.getTrade_status().equals("TRADE_FINISH")){
            //??????????????????
            String outTradeNo = vo.getOut_trade_no();
            this.baseMapper.updateOrderStatus(outTradeNo,OrderStatusEnum.PAYED.getCode());
        }
        return "success";
    }

    @Override
    public void closeSeckillOrder(SeckillOrderTo orderTo) {
        //???????????????
        OrderEntity orderEntity=new OrderEntity();
        orderEntity.setOrderSn(orderTo.getOrderSn());
        orderEntity.setMemberId(orderTo.getMemberId());
        orderEntity.setStatus(OrderStatusEnum.CREATE_NEW.getCode());
        BigDecimal multiply = orderTo.getSeckillPrice().multiply(new BigDecimal("" + orderTo.getNum()));
        orderEntity.setPayAmount(multiply);
        this.save(orderEntity);
        //????????????????????????
        OrderItemEntity orderItemEntity = new OrderItemEntity();
        orderItemEntity.setOrderSn(orderTo.getOrderSn());
        orderItemEntity.setRealAmount(multiply);
        //????????????sku???????????????
        orderItemEntity.setSkuQuantity(orderTo.getNum());
        orderItemService.save(orderItemEntity);
    }


    private void saveOrder(OrderCreateTo createTo) {
        OrderEntity orderEntity = createTo.getOrderEntity();
        orderEntity.setModifyTime(new Date());
//        orderDao.insert(orderEntity);
        this.save(orderEntity);
        List<OrderItemEntity> orderItemEntities = createTo.getOrderItems();
//        for (OrderItemEntity orderItemEntity : orderItemEntities) {
//            orderItemDao.insert(orderItemEntity);
//        }
        orderItemService.saveBatch(orderItemEntities);
    }

    /**
     * ????????????
     *
     * @return
     */
    public OrderCreateTo creatOrder() {
        OrderCreateTo createTo = new OrderCreateTo();
        //1??????????????????
        String orderSn = IdWorker.getTimeId();
        //???????????????
        OrderEntity orderEntity = buildOrder(orderSn);
        createTo.setOrderEntity(orderEntity);
        //2???????????????????????????
        List<OrderItemEntity> orderItemEntities = buildOrderItems(orderSn);
        //3?????????????????????
        computePrice(orderEntity, orderItemEntities);
        createTo.setOrderItems(orderItemEntities);
        return createTo;
    }

    private void computePrice(OrderEntity orderEntity, List<OrderItemEntity> orderItemEntities) {
//        BigDecimal bigDecimal = new BigDecimal("0.0");
//        for (OrderItemEntity orderItemEntity : orderItemEntities) {
//            BigDecimal realCount = orderItemEntity.getRealAmount();
//            bigDecimal = bigDecimal.add(realCount);
//        }
//        orderEntity.setTotalAmount(bigDecimal);
//        orderEntity.setPayAmount(bigDecimal.add(orderEntity.getFreightAmount()));
        //??????
        BigDecimal total = new BigDecimal("0.0");
        //?????????
        BigDecimal coupon = new BigDecimal("0.0");
        BigDecimal intergration = new BigDecimal("0.0");
        BigDecimal promotion = new BigDecimal("0.0");

        //??????????????????
        Integer integrationTotal = 0;
        Integer growthTotal = 0;
        //??????????????????????????????????????????????????????
        for (OrderItemEntity orderItem : orderItemEntities) {
            //??????????????????
            coupon = coupon.add(orderItem.getCouponAmount());
            promotion = promotion.add(orderItem.getPromotionAmount());
            intergration = intergration.add(orderItem.getIntegrationAmount());
            //??????
            total = total.add(orderItem.getRealAmount());
            //??????????????????????????????
            integrationTotal += orderItem.getGiftIntegration();
            growthTotal += orderItem.getGiftGrowth();

        }
        //1????????????????????????
        orderEntity.setTotalAmount(total);
        //??????????????????(??????+??????)
        orderEntity.setPayAmount(total.add(orderEntity.getFreightAmount()));
        orderEntity.setCouponAmount(coupon);
        orderEntity.setPromotionAmount(promotion);
        orderEntity.setIntegrationAmount(intergration);

        //???????????????????????????
        orderEntity.setIntegration(integrationTotal);
        orderEntity.setGrowth(growthTotal);

        //??????????????????(0-????????????1-?????????)
        orderEntity.setDeleteStatus(0);
    }

    /**
     * ???????????????
     *
     * @return
     */
    private List<OrderItemEntity> buildOrderItems(String orderSn) {
        List<OrderItemEntity> collect = new ArrayList<>();
        //????????????????????????????????????
        List<OrderItemVo> currentUserCartItems = cartFeignService.getCurrentUserCartItems();
        if (currentUserCartItems != null && currentUserCartItems.size() > 0) {
            collect = currentUserCartItems.stream().map(item -> {
                OrderItemEntity orderItemEntity = buildOrderItem(item);
                orderItemEntity.setOrderSn(orderSn);
                return orderItemEntity;
            }).collect(Collectors.toList());
        }
        return collect;
    }

    /**
     * ????????????????????????
     *
     * @param item
     * @return
     */
    private OrderItemEntity buildOrderItem(OrderItemVo item) {
        OrderItemEntity orderItemEntity = new OrderItemEntity();

        //1????????????spu??????
        Long skuId = item.getSkuId();
        //??????spu?????????
        R spuInfo = productFeignService.getSpuInfoBySkuId(skuId);
        SpuInfoVo spuInfoData = spuInfo.getData(new TypeReference<SpuInfoVo>() {
        });
        orderItemEntity.setSpuId(spuInfoData.getId());
        orderItemEntity.setSpuName(spuInfoData.getSpuName());
        orderItemEntity.setSpuBrand(spuInfoData.getBrandId().toString());
        orderItemEntity.setCategoryId(spuInfoData.getCatalogId());

        //2????????????sku??????
        orderItemEntity.setSkuId(skuId);
        orderItemEntity.setSkuName(item.getTitle());
        orderItemEntity.setSkuPic(item.getImage());
        orderItemEntity.setSkuPrice(item.getPrice());
        orderItemEntity.setSkuQuantity(item.getCount());

        //??????StringUtils.collectionToDelimitedString???list???????????????String
        String skuAttrValues = StringUtils.collectionToDelimitedString(item.getSkuAttr(), ";");
        orderItemEntity.setSkuAttrsVals(skuAttrValues);

        //3????????????????????????

        //4????????????????????????
        orderItemEntity.setGiftGrowth(item.getPrice().multiply(new BigDecimal(item.getCount())).intValue());
        orderItemEntity.setGiftIntegration(item.getPrice().multiply(new BigDecimal(item.getCount())).intValue());

        //5???????????????????????????
        orderItemEntity.setPromotionAmount(BigDecimal.ZERO);
        orderItemEntity.setCouponAmount(BigDecimal.ZERO);
        orderItemEntity.setIntegrationAmount(BigDecimal.ZERO);

        //??????????????????????????????.?????? - ??????????????????
        //???????????????
        BigDecimal origin = orderItemEntity.getSkuPrice().multiply(new BigDecimal(orderItemEntity.getSkuQuantity().toString()));
        //??????????????????????????????????????????
        BigDecimal subtract = origin.subtract(orderItemEntity.getCouponAmount())
                .subtract(orderItemEntity.getPromotionAmount())
                .subtract(orderItemEntity.getIntegrationAmount());
        orderItemEntity.setRealAmount(subtract);

        return orderItemEntity;
    }

    /**
     * ??????????????????
     *
     * @param orderSn
     * @return
     */
    private OrderEntity buildOrder(String orderSn) {
        MemberRespVo memberRespVo = LoginUserInterceptor.loginUser.get();
        OrderEntity orderEntity = new OrderEntity();
        orderEntity.setOrderSn(orderSn);
        orderEntity.setMemberId(memberRespVo.getId());

        //????????????????????????
        OrderSubmitVo orderSubmitVo = confirmVoThreadLocal.get();
        R fare = wmsFeignService.getFare(orderSubmitVo.getAddrId());
        FareVo fareVo = fare.getData(new TypeReference<FareVo>() {
        });
        //??????????????????
        orderEntity.setFreightAmount(fareVo.getFare());
        //?????????????????????
        orderEntity.setReceiverCity(fareVo.getAddress().getCity());
        orderEntity.setReceiverDetailAddress(fareVo.getAddress().getDetailAddress());
        orderEntity.setReceiverName(fareVo.getAddress().getName());
        orderEntity.setReceiverPostCode(fareVo.getAddress().getPostCode());
        orderEntity.setReceiverProvince(fareVo.getAddress().getProvince());
        orderEntity.setReceiverRegion(fareVo.getAddress().getRegion());
        //???????????????????????????
        orderEntity.setStatus(OrderStatusEnum.CREATE_NEW.getCode());
        orderEntity.setAutoConfirmDay(7);
        return orderEntity;
    }

}