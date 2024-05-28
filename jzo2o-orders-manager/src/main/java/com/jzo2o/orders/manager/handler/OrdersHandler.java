package com.jzo2o.orders.manager.handler;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.jzo2o.api.trade.RefundRecordApi;
import com.jzo2o.api.trade.dto.response.ExecutionResultResDTO;
import com.jzo2o.api.trade.enums.RefundStatusEnum;
import com.jzo2o.common.constants.UserType;
import com.jzo2o.orders.base.enums.OrderRefundStatusEnum;
import com.jzo2o.orders.base.mapper.OrdersMapper;
import com.jzo2o.orders.base.model.domain.Orders;
import com.jzo2o.orders.base.model.domain.OrdersCanceled;
import com.jzo2o.orders.base.model.domain.OrdersRefund;
import com.jzo2o.orders.manager.model.dto.OrderCancelDTO;
import com.jzo2o.orders.manager.service.IOrdersCreateService;
import com.jzo2o.orders.manager.service.IOrdersManagerService;
import com.jzo2o.orders.manager.service.IOrdersRefundService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.List;

/**
 * @author zwy
 * @version 1.0
 * @description: TODO
 * @date 2024/5/17 18:32
 */
@Component
public class OrdersHandler {

    @Resource
    private IOrdersCreateService ordersCreateService;
    @Resource
    private IOrdersManagerService ordersManagerService;
    @Resource
    private RefundRecordApi refundRecordApi;
    @Resource
    private OrdersHandler ordersHandler;
    @Resource
    private IOrdersRefundService ordersRefundService;
    @Resource
    private OrdersMapper ordersMapper;

    @XxlJob(value = "cancelOverTimePayOrder")
    public void cancelOverTimePayOrder() {
        List<Orders> ordersList = ordersCreateService.queryOverTimePayOrdersListByCount(100);
        if (CollUtil.isEmpty(ordersList)) {
            XxlJobHelper.log("查询超时订单列表为空！");
            return;
        }
        for (Orders order : ordersList) {
            OrderCancelDTO orderCancelDTO  = BeanUtil.toBean(order, OrderCancelDTO.class);
            //系统调用的 没有人员名称和id
            orderCancelDTO.setCurrentUserType(UserType.SYSTEM);
            orderCancelDTO.setCancelReason("订单超时支付，自动取消");
            ordersManagerService.cancel(orderCancelDTO);
        }
    }

    @XxlJob(value = "handleRefundOrders")
    public void handleRefundOrders() {
        List<OrdersRefund> ordersRefunds = ordersRefundService.queryRefundOrderListByCount(100);
        for(OrdersRefund ordersRefund : ordersRefunds) {
            requestRefundOrder(ordersRefund);
        }
    }

    /**
     * 请求退款
     * @param ordersRefund 退款记录
     */
    public void requestRefundOrder(OrdersRefund ordersRefund){
        //调用第三方进行退款
        ExecutionResultResDTO executionResultResDTO = null;
        executionResultResDTO = refundRecordApi.refundTrading(ordersRefund.getTradingOrderNo(), ordersRefund.getRealPayAmount());
        if(executionResultResDTO!=null){
            //退款后处理订单相关信息
            ordersHandler.refundOrder(ordersRefund, executionResultResDTO);
        }
    }
    /**
     * 更新退款状态
     * @param ordersRefund
     * @param executionResultResDTO
     */
    @Transactional(rollbackFor = Exception.class)
    public void refundOrder(OrdersRefund ordersRefund, ExecutionResultResDTO executionResultResDTO) {
        //根据响应结果更新退款状态
        int refundStatus = OrderRefundStatusEnum.REFUNDING.getStatus();//退款中
        if (ObjectUtil.equal(RefundStatusEnum.SUCCESS.getCode(), executionResultResDTO.getRefundStatus())) {
            //退款成功
            refundStatus = OrderRefundStatusEnum.REFUND_SUCCESS.getStatus();
        } else if (ObjectUtil.equal(RefundStatusEnum.FAIL.getCode(), executionResultResDTO.getRefundStatus())) {
            //退款失败
            refundStatus = OrderRefundStatusEnum.REFUND_FAIL.getStatus();
        }
        if(ObjectUtil.equal(refundStatus,OrderRefundStatusEnum.REFUNDING.getStatus())){
            return; // 退款中直接返回
        }
        //非退款中，则更新退款状态
        LambdaUpdateWrapper<Orders> updateWrapper = new LambdaUpdateWrapper<Orders>()
                .eq(Orders::getId, ordersRefund.getId())
                .ne(Orders::getRefundStatus, refundStatus)
                .set(Orders::getRefundStatus, refundStatus)
                .set(ObjectUtil.isNotEmpty(executionResultResDTO.getRefundId()), Orders::getRefundId, executionResultResDTO.getRefundId())
                .set(ObjectUtil.isNotEmpty(executionResultResDTO.getRefundNo()), Orders::getRefundNo, executionResultResDTO.getRefundNo());
        int update = ordersMapper.update(null,updateWrapper);
        //非退款中状态，删除申请退款记录，删除后定时任务不再扫描
        if(update>0){
            //非退款中状态，删除申请退款记录，删除后定时任务不再扫描
            ordersRefundService.removeById(ordersRefund.getId());
        }
    }


    /**
     * 新启动一个线程请求退款
     * @param ordersRefundId
     */
    public void requestRefundNewThread(Long ordersRefundId){
        new Thread(()->{
            OrdersRefund ordersRefund = ordersRefundService.getById(ordersRefundId);
            if(ObjectUtil.isNotNull(ordersRefund)) {
                requestRefundOrder(ordersRefund);
            }
        }).start();
    }
}
