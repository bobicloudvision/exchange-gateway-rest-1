package exchange.core2.rest;

import com.alibaba.fastjson.JSON;

import com.google.common.util.concurrent.RateLimiter;
import exchange.core2.core.ExchangeApi;
import exchange.core2.core.ExchangeCore;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.rest.commands.ApiErrorCodes;
import exchange.core2.rest.commands.RestApiPlaceOrder;
import exchange.core2.rest.commands.util.ArithmeticHelper;
import exchange.core2.rest.controllers.RestControllerHelper;
import exchange.core2.rest.model.internal.GatewaySymbolSpec;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.enums.ReadyState;
import org.java_websocket.handshake.ServerHandshake;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.*;

@Component
@Slf4j
@RequiredArgsConstructor
public class CoinbaseTrader {
    private static final RateLimiter rateLimiter = RateLimiter.create(1);

    private final ExecutorService executor = Executors.newFixedThreadPool(1);
    private final ScheduledExecutorService scheduledExecutor = Executors.newScheduledThreadPool(1);


    @Autowired
    private ExchangeCore exchangeCore;

    @Autowired
    private GatewayState gatewayState;

    @PostConstruct
    public void init() throws URISyntaxException {

        System.out.println("start");

        MyClient client = new MyClient(new URI("wss://ws-feed.exchange.coinbase.com"));

        scheduledExecutor.scheduleAtFixedRate(() -> {
            try {
              //  test(user);
//                if (true) {
//                    return;
//                }

                if (!client.isOpen()) {
                    try {
                        if (client.getReadyState().equals(ReadyState.NOT_YET_CONNECTED)) {
                            System.out.println("connecting...: {}" + client.getURI());
                            client.connectBlocking();
                        } else if (client.getReadyState().equals(ReadyState.CLOSING) || client.getReadyState().equals(
                                ReadyState.CLOSED)) {
                            System.out.println("reconnecting...: {}" +  client.getURI());
                            client.reconnectBlocking();
                        }
                    } catch (Exception e) {
                        System.out.println("ws error " + e);
                    }
                } else {
                    client.sendPing();
                }
            } catch (Exception e) {
                System.out.println("send ping error: {}" + e.getMessage() +  e);
            }
        }, 0, 3000, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void destroy() {
        executor.shutdown();
        scheduledExecutor.shutdown();
    }

//    public void test(User user) {
//        PlaceOrderRequest order = new PlaceOrderRequest();
//        order.setProductId("BTC-USDT");
//        order.setClientOid(UUID.randomUUID().toString());
//        order.setPrice(String.valueOf(new Random().nextInt(10) + 1));
//        order.setSize(String.valueOf(new Random().nextInt(10) + 1));
//        order.setFunds(String.valueOf(new Random().nextInt(10) + 1));
//        order.setSide(new Random().nextBoolean() ? "BUY" : "SELL");
//        order.setType("limit");
//        orderController.placeOrder(order, user);
//    }

    @Getter
    @Setter
    public static class ChannelMessage {
        private String type;
        private String product_id;
        private long tradeId;
        private long sequence;
        private String taker_order_id;
        private String maker_order_id;
        private String time;
        private Long size;
        private BigDecimal price;
        private String side;
        private String orderId;
        private String remaining_size;
        private String funds;
        private String order_type;
        private String reason;
        private String best_bid;
        private String best_bid_size;
        private String best_ask;
        private String best_ask_size;
    }

    public class MyClient extends org.java_websocket.client.WebSocketClient {
      //  private final User user;

        public MyClient(URI serverUri) {
            super(serverUri, new Draft_6455(), null, 1000);
         //   this.user = user;

        }

        @Override
        public void onOpen(ServerHandshake serverHandshake) {
            System.out.println("open");

            send("{\n" +
                    "    \"type\": \"subscribe\",\n" +
                    "    \"product_ids\": [\n" +
                    "        \"BTC-USD\"\n" +
                    "    ],\n" +
                    "    \"channels\": [\"ticker\"]\n" +
                    "}");
        }

        @Override
        public void onMessage(String s) {
            if (!rateLimiter.tryAcquire()) {
                return;
            }
            executor.execute(() -> {
                try {
                    ChannelMessage message = JSON.parseObject(s, ChannelMessage.class);
                    String productId = message.getProduct_id() + "T";

                    switch (message.getType()) {
                        case "ticker":
                            //System.out.println(JSON.toJSONString(message));
                            if (message.getPrice() != null) {

                                System.out.println(message.getPrice());
                                System.out.println(message.getSide().toUpperCase());


                                // Parse the string to a double
                                double decimalBestBitSize = Double.parseDouble(message.best_bid_size);

                                // Multiply by a large number (e.g., 1e6 for 6 decimal places)
                                long orderSize = (long)(decimalBestBitSize * 1e8);

                                System.out.println(orderSize);

                                long uid = 4444;
                                String symbol = "BTC_USDT_PERP";

                                OrderAction orderAction = OrderAction.ASK;

                                if (message.getSide().equalsIgnoreCase("BUY")) {
                                    orderAction = orderAction.BID;
                                }

                                RestApiPlaceOrder placeOrder = new RestApiPlaceOrder(
                                        message.getPrice(),
                                        orderSize,
                                        0,
                                        orderAction,
                                        OrderType.GTC
                                );

                                GatewaySymbolSpec symbolSpec = gatewayState.getSymbolSpec(symbol);
                                if (symbolSpec == null) {
                                    return;
                                }

                                final BigDecimal priceInQuoteCurrencyUnits = ArithmeticHelper.toBaseUnits(placeOrder.getPrice(), symbolSpec.quoteCurrency);
                                if (!ArithmeticHelper.isIntegerNotNegativeValue(priceInQuoteCurrencyUnits)) {
                                    return ;
                                }

                                final long price = priceInQuoteCurrencyUnits.longValue();

                                // TODO perform conversions

                                ExchangeApi api = exchangeCore.getApi();
                                CompletableFuture<OrderCommand> future = new CompletableFuture<>();


                                long orderId = api.placeNewOrder(
                                        placeOrder.getUserCookie(),
                                        price,
                                        price, // same price (can not move bids up in exchange mode)
                                        placeOrder.getSize(),
                                        placeOrder.getAction(),
                                        placeOrder.getOrderType(),
                                        symbolSpec.symbolId,
                                        uid,
                                        future::complete);

                                System.out.println("placing orderId {}" + orderId);

                                // TODO can be inserted after events - insert into cookie-based queue first?
                                gatewayState.getOrCreateUserProfile(uid).addNewOrder(orderId, symbol, placeOrder);

                            }
                            break;
                        case "done":
                            System.out.println("ORDER DONEEE");
                            //adminController.cancelOrder(message.getOrderId(), productId);
                            break;
                        default:
                    }
                } catch (Exception e) {
                    System.out.println("error: {}" + e.getMessage() + e);
                }
            });
        }

        @Override
        public void onClose(int i, String s, boolean b) {
            System.out.println("connection closed");
        }

        @Override
        public void onError(Exception e) {
            System.out.println("error" + e);
        }
    }
}
