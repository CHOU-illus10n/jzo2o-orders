package com.jzo2o.orders.manager.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.db.DbRuntimeException;
import cn.hutool.db.sql.Order;
import com.baomidou.mybatisplus.core.injector.methods.SelectById;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jzo2o.api.customer.AddressBookApi;
import com.jzo2o.api.customer.dto.response.AddressBookResDTO;
import com.jzo2o.api.foundations.ServeApi;
import com.jzo2o.api.foundations.dto.response.ServeAggregationResDTO;
import com.jzo2o.api.market.CouponApi;
import com.jzo2o.api.market.dto.request.CouponUseReqDTO;
import com.jzo2o.api.market.dto.response.AvailableCouponsResDTO;
import com.jzo2o.api.market.dto.response.CouponUseResDTO;
import com.jzo2o.api.trade.NativePayApi;
import com.jzo2o.api.trade.TradingApi;
import com.jzo2o.api.trade.dto.request.NativePayReqDTO;
import com.jzo2o.api.trade.dto.response.NativePayResDTO;
import com.jzo2o.api.trade.dto.response.TradingResDTO;
import com.jzo2o.api.trade.enums.PayChannelEnum;
import com.jzo2o.api.trade.enums.TradingStateEnum;
import com.jzo2o.common.expcetions.BadRequestException;
import com.jzo2o.common.expcetions.CommonException;
import com.jzo2o.common.model.msg.TradeStatusMsg;
import com.jzo2o.common.utils.BeanUtils;
import com.jzo2o.common.utils.DateUtils;
import com.jzo2o.common.utils.NumberUtils;
import com.jzo2o.common.utils.ObjectUtils;
import com.jzo2o.mvc.utils.UserContext;
import com.jzo2o.orders.base.config.OrderStateMachine;
import com.jzo2o.orders.base.constants.RedisConstants;
import com.jzo2o.orders.base.enums.OrderPayStatusEnum;
import com.jzo2o.orders.base.enums.OrderStatusEnum;
import com.jzo2o.orders.base.mapper.OrdersMapper;
import com.jzo2o.orders.base.model.domain.Orders;
import com.jzo2o.orders.base.model.domain.OrdersCanceled;
import com.jzo2o.orders.base.model.domain.OrdersRefund;
import com.jzo2o.orders.base.model.dto.OrderSnapshotDTO;
import com.jzo2o.orders.base.model.dto.OrderUpdateStatusDTO;
import com.jzo2o.orders.base.service.IOrdersCommonService;
import com.jzo2o.orders.manager.model.dto.OrderCancelDTO;
import com.jzo2o.orders.manager.model.dto.request.OrdersPayReqDTO;
import com.jzo2o.orders.manager.model.dto.request.PlaceOrderReqDTO;
import com.jzo2o.orders.manager.model.dto.response.OrdersPayResDTO;
import com.jzo2o.orders.manager.model.dto.response.PlaceOrderResDTO;
import com.jzo2o.orders.manager.porperties.TradeProperties;
import com.jzo2o.orders.manager.service.IOrdersCanceledService;
import com.jzo2o.orders.manager.service.IOrdersCreateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static com.jzo2o.common.constants.ErrorInfo.Code.TRADE_FAILED;
import static com.jzo2o.orders.base.constants.RedisConstants.Lock.ORDERS_SHARD_KEY_ID_GENERATOR;

/**
 * <p>
 * 下单服务类
 * </p>
 *
 * @author itcast
 * @since 2023-07-10
 */
@Slf4j
@Service
public class OrdersCreateServiceImpl extends ServiceImpl<OrdersMapper, Orders> implements IOrdersCreateService {

    @Resource
    private RedisTemplate<String, Long> redisTemplate;
    @Resource
    private AddressBookApi addressBookApi;
    @Resource
    private ServeApi serveApi;
    @Resource
    private IOrdersCreateService owner;
    @Resource
    private TradeProperties tradeProperties;//拿到nacos配置文件
    @Resource
    private NativePayApi nativePayApi;
    @Resource
    private TradingApi tradingApi;
    @Resource
    private OrderStateMachine orderStateMachine;
    @Resource
    private CouponApi couponApi;

    /**
     * 生成订单id {yyMMdd}{13位id}
     * @return
     */
    private Long generateOrderId(){
        Long id = redisTemplate.opsForValue().increment(ORDERS_SHARD_KEY_ID_GENERATOR, 1);
        return DateUtils.getFormatDate(LocalDateTime.now(), "yyMMdd") * 10000000000000L + id;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addWithCoupon(Orders orders, Long couponId) {
        CouponUseReqDTO couponUseReqDTO = new CouponUseReqDTO();
        couponUseReqDTO.setOrdersId(orders.getId());
        couponUseReqDTO.setId(couponId);
        couponUseReqDTO.setTotalAmount(orders.getTotalAmount());
        CouponUseResDTO couponUseResDTO = couponApi.use(couponUseReqDTO);
        orders.setDiscountAmount(couponUseResDTO.getDiscountAmount());
        BigDecimal realPay = orders.getTotalAmount().subtract(couponUseResDTO.getDiscountAmount());
        orders.setRealPayAmount(realPay);
        //保存订单
        add(orders);
    }

    @Override
    public List<AvailableCouponsResDTO> getAvailableCoupons(Long serveId, Integer purNum) {
        //获取服务
        ServeAggregationResDTO serveAggregationResDTO = serveApi.findById(serveId);
        if(serveAggregationResDTO == null || serveAggregationResDTO.getSaleStatus() !=2){
            throw new BadRequestException("服务不可用");
        }
        //计算订单总金额
        BigDecimal totalAmount = serveAggregationResDTO.getPrice().multiply(new BigDecimal(purNum));
        List<AvailableCouponsResDTO> available = couponApi.getAvailable(totalAmount);
        return available;
    }

    @Override
    public PlaceOrderResDTO placeOrder(PlaceOrderReqDTO placeOrderReqDTO) {

        Long addressBookId = placeOrderReqDTO.getAddressBookId();

        AddressBookResDTO detail = addressBookApi.detail(addressBookId);
        if (detail == null) {
            throw new BadRequestException("预约地址异常，无法下单");
        }
        ServeAggregationResDTO serveResDTO = serveApi.findById(placeOrderReqDTO.getServeId());
        if (serveResDTO == null || serveResDTO.getSaleStatus() != 2) {
            throw new BadRequestException("服务不可用");
        }

        // 2.下单前数据准备
        Orders orders = new Orders();
        // id 订单id
        orders.setId(generateOrderId());
        // userId，从threadLocal获取当前登录用户的id，通过UserContextInteceptor拦截进行设置
        orders.setUserId(UserContext.currentUserId());
        // 服务id
        orders.setServeId(placeOrderReqDTO.getServeId());
        // 服务项id
        orders.setServeItemId(serveResDTO.getServeItemId());
        orders.setServeItemName(serveResDTO.getServeItemName());
        orders.setServeItemImg(serveResDTO.getServeItemImg());
        orders.setUnit(serveResDTO.getUnit());
        //服务类型信息
        orders.setServeTypeId(serveResDTO.getServeTypeId());
        orders.setServeTypeName(serveResDTO.getServeTypeName());
        // 订单状态
        orders.setOrdersStatus(0);
        // 支付状态，暂不支持，初始化一个空状态
        orders.setPayStatus(OrderPayStatusEnum.NO_PAY.getStatus());
        // 服务时间
        orders.setServeStartTime(placeOrderReqDTO.getServeStartTime());
        // 城市编码
        orders.setCityCode(serveResDTO.getCityCode());
        // 地理位置
        orders.setLon(detail.getLon());
        orders.setLat(detail.getLat());

        String serveAddress = new StringBuffer(detail.getProvince())
                .append(detail.getCity())
                .append(detail.getCounty())
                .append(detail.getAddress())
                .toString();
        orders.setServeAddress(serveAddress);
        // 联系人
        orders.setContactsName(detail.getName());
        orders.setContactsPhone(detail.getPhone());

        // 价格
        orders.setPrice(serveResDTO.getPrice());
        // 购买数量
        orders.setPurNum(NumberUtils.null2Default(placeOrderReqDTO.getPurNum(), 1));
        // 订单总金额 价格 * 购买数量
        orders.setTotalAmount(orders.getPrice().multiply(new BigDecimal(orders.getPurNum())));

        // 优惠金额 当前默认0
        orders.setDiscountAmount(BigDecimal.ZERO);
        // 实付金额 订单总金额 - 优惠金额
        orders.setRealPayAmount(NumberUtils.sub(orders.getTotalAmount(), orders.getDiscountAmount()));
        //排序字段,根据服务开始时间转为毫秒时间戳+订单后5位
        long sortBy = DateUtils.toEpochMilli(orders.getServeStartTime()) + orders.getId() % 100000;
        orders.setSortBy(sortBy);
        //保存订单
        if(ObjectUtils.isNotNull(placeOrderReqDTO.getCouponId())){
            owner.addWithCoupon(orders, placeOrderReqDTO.getCouponId());
        }else{
            owner.add(orders);
        }


        return new PlaceOrderResDTO(orders.getId());
    }

    @Transactional(rollbackFor = Exception.class)
    public void add(Orders orders) {
        boolean save = this.save(orders);
        if (!save) {
            throw new DbRuntimeException("下单失败");
        }
        //构建快照对象
        OrderSnapshotDTO orderSnapshotDTO = BeanUtil.toBean(baseMapper.selectById(orders.getId()), OrderSnapshotDTO.class);
        //状态机启动
        orderStateMachine.start(null,String.valueOf(orders.getId()),orderSnapshotDTO);
    }

    @Override
    public OrdersPayResDTO pay(Long id, OrdersPayReqDTO ordersPayReqDTO) {
        Orders orders =  baseMapper.selectById(id);
        if (ObjectUtil.isNull(orders)) {
            throw new CommonException(TRADE_FAILED, "订单不存在");
        }
        //订单的支付状态为成功直接返回
        if (OrderPayStatusEnum.PAY_SUCCESS.getStatus() == orders.getPayStatus()
                && ObjectUtil.isNotEmpty(orders.getTradingOrderNo())) {
            OrdersPayResDTO ordersPayResDTO = new OrdersPayResDTO();
            BeanUtil.copyProperties(orders, ordersPayResDTO);
            ordersPayResDTO.setProductOrderNo(orders.getId());
            return ordersPayResDTO;
        } else {
            //生成二维码
            NativePayResDTO nativePayResDTO = generateQrCode(orders, ordersPayReqDTO.getTradingChannel());
            OrdersPayResDTO ordersPayResDTO = BeanUtil.toBean(nativePayResDTO, OrdersPayResDTO.class);
            return ordersPayResDTO;
        }
    }

    @Override
    public OrdersPayResDTO getPayResultFromTradServer(Long id) {
        //查询订单
        Orders orders = baseMapper.selectById(id);
        if(ObjectUtil.isNull(orders)){
            throw new CommonException(TRADE_FAILED, "订单不存在");
        }
        //支付结果
        Integer payStatus = orders.getPayStatus();
        //未支付且已存在支付服务的交易单号此时远程调用支付服务查询支付结果
        if(payStatus == OrderPayStatusEnum.NO_PAY.getStatus() &&
            ObjectUtil.isNotEmpty(orders.getTradingOrderNo())){
            TradingResDTO tradingResDTO = tradingApi.findTradResultByTradingOrderNo(orders.getTradingOrderNo());
            //如果支付成功这里更新订单状态
            if (ObjectUtil.isNotNull(tradingResDTO)
                    && ObjectUtil.equals(tradingResDTO.getTradingState(), TradingStateEnum.YJS)) {
                TradeStatusMsg msg = TradeStatusMsg.builder()
                        .tradingOrderNo(orders.getTradingOrderNo())
                        .productOrderNo(orders.getId())
                        .transactionId(tradingResDTO.getTransactionId())
                        .tradingChannel(orders.getTradingChannel())
                        .statusCode(TradingStateEnum.YJS.getCode())
                        .build();
                owner.paySuccess(msg);
                //构造返回数据
                OrdersPayResDTO ordersPayResDTO = BeanUtils.toBean(msg, OrdersPayResDTO.class);
                ordersPayResDTO.setPayStatus(OrderPayStatusEnum.PAY_SUCCESS.getStatus());
                return ordersPayResDTO;
            }

        }
        //返回未支付的订单信息
        OrdersPayResDTO ordersPayResDTO = new OrdersPayResDTO();
        ordersPayResDTO.setPayStatus(payStatus);
        ordersPayResDTO.setProductOrderNo(orders.getId());
        ordersPayResDTO.setTradingOrderNo(orders.getTradingOrderNo());
        ordersPayResDTO.setTradingChannel(orders.getTradingChannel());
        return ordersPayResDTO;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void paySuccess(TradeStatusMsg tradeStatusMsg) {
        //查询订单
        Orders orders = baseMapper.selectById(tradeStatusMsg.getProductOrderNo());
        if (ObjectUtil.isNull(orders)) {
            throw new CommonException(TRADE_FAILED, "订单不存在");
        }
        //校验支付状态如果不是待支付状态则不作处理
        if (ObjectUtil.notEqual(OrderPayStatusEnum.NO_PAY.getStatus(), orders.getPayStatus())) {
            log.info("更新订单支付成功，当前订单:{}支付状态不是待支付状态", orders.getId());
            return;
        }
        //校验订单状态如果不是待支付状态则不作处理
        if (ObjectUtils.notEqual(OrderStatusEnum.NO_PAY.getStatus(),orders.getOrdersStatus())) {
            log.info("更新订单支付成功，当前订单:{}状态不是待支付状态", orders.getId());
        }

        //第三方支付单号校验
        if (ObjectUtil.isEmpty(tradeStatusMsg.getTransactionId())) {
            throw new CommonException("支付成功通知缺少第三方支付单号");
        }
        //更新订单的支付状态及第三方交易单号等信息
        boolean update = lambdaUpdate()
                .eq(Orders::getId, orders.getId())
                .set(Orders::getPayTime, LocalDateTime.now())//支付时间
                .set(Orders::getTradingOrderNo, tradeStatusMsg.getTradingOrderNo())//交易单号
                .set(Orders::getTradingChannel, tradeStatusMsg.getTradingChannel())//支付渠道
                .set(Orders::getTransactionId, tradeStatusMsg.getTransactionId())//第三方支付交易号
                .set(Orders::getPayStatus, OrderPayStatusEnum.PAY_SUCCESS.getStatus())//支付状态
                .set(Orders::getOrdersStatus, OrderStatusEnum.DISPATCHING.getStatus())//订单状态更新为派单中
                .update();
        if(!update){
            log.info("更新订单:{}支付成功失败", orders.getId());
            throw new CommonException("更新订单"+orders.getId()+"支付成功失败");
        }

    }

    @Override
    public List<Orders> queryOverTimePayOrdersListByCount(Integer count) {
        List<Orders> orders = lambdaQuery()
                .eq(Orders::getPayStatus, OrderPayStatusEnum.NO_PAY.getStatus())
                .lt(Orders::getCreateTime,LocalDateTime.now().minusMinutes(15))
                .last("limit "+count)
                .list();
        return orders;
    }


    private NativePayResDTO generateQrCode(Orders orders, PayChannelEnum tradingChannel) {
        //判断支付渠道
        Long enterpriseId = ObjectUtil.equal(PayChannelEnum.ALI_PAY, tradingChannel) ?
                tradeProperties.getAliEnterpriseId() : tradeProperties.getWechatEnterpriseId();

        //构建支付请求参数
        NativePayReqDTO nativePayReqDTO = new NativePayReqDTO();
        nativePayReqDTO.setEnterpriseId(enterpriseId);
        nativePayReqDTO.setProductOrderNo(orders.getId());
        nativePayReqDTO.setMemo(orders.getServeItemName());
        nativePayReqDTO.setTradingAmount(orders.getRealPayAmount());
        nativePayReqDTO.setProductAppId("jzo2o.orders");
        nativePayReqDTO.setTradingChannel(tradingChannel);

        //判断是否切换支付渠道 不为空且传入的和订单的支付渠道不一致
        if (ObjectUtil.isNotEmpty(orders.getTradingChannel())
                && ObjectUtil.notEqual(orders.getTradingChannel(), tradingChannel.toString())) {
            nativePayReqDTO.setChangeChannel(true);
        }
        //生成支付二维码
        NativePayResDTO downLineTrading = nativePayApi.createDownLineTrading(nativePayReqDTO);
        if(ObjectUtils.isNotNull(downLineTrading)){
            log.info("订单:{}请求支付,生成二维码:{}",orders.getId(),downLineTrading.toString());
            //将二维码更新到交易订单中
            boolean update = lambdaUpdate()
                    .eq(Orders::getId, downLineTrading.getProductOrderNo())
                    .set(Orders::getTradingOrderNo, downLineTrading.getTradingOrderNo())
                    .set(Orders::getTradingChannel, downLineTrading.getTradingChannel())
                    .update();
            if(!update){
                throw new CommonException("订单:"+orders.getId()+"请求支付更新交易单号失败");
            }
        }
        return downLineTrading;
    }
}
