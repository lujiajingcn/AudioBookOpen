package com.audiobook.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableAsync
// 开启定时任务：CreditOrderService#closeExpiredOrders 依赖 @Scheduled 关闭超时未支付订单，
// 少了这个注解，待支付订单会永久堆积在 PENDING 状态。
@EnableScheduling
@SpringBootApplication
public class ServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(ServerApplication.class, args);
    }
}
