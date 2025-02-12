/*
 * Copyright 2019 Maksim Zheravin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package exchange.core2.rest.controllers;

import exchange.core2.core.ExchangeApi;
import exchange.core2.core.ExchangeCore;
import exchange.core2.core.common.BalanceAdjustmentType;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.api.binary.BatchAddSymbolsCommand;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.rest.GatewayState;
import exchange.core2.rest.commands.ApiErrorCodes;
import exchange.core2.rest.commands.admin.RestApiAccountBalanceAdjustment;
import exchange.core2.rest.commands.admin.RestApiAddSymbol;
import exchange.core2.rest.commands.admin.RestApiAddUser;
import exchange.core2.rest.commands.admin.RestApiAdminAsset;
import exchange.core2.rest.commands.util.ArithmeticHelper;
import exchange.core2.rest.events.RestGenericResponse;
import exchange.core2.rest.model.internal.GatewayAssetSpec;
import exchange.core2.rest.model.internal.GatewaySymbolSpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

@Slf4j
@RestController
@RequestMapping(value = "syncAdminApi/v1/", produces = MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8")
public class SyncAdminApiSymbolsController {

    @Autowired
    private ExchangeCore exchangeCore;

    @Autowired
    private GatewayState gatewayState;


    @RequestMapping(value = "insertSimpleData", method = RequestMethod.POST)
    public ResponseEntity<RestGenericResponse> insertSimpleData() throws ExecutionException, InterruptedException {

        final GatewayAssetSpec specBTC = GatewayAssetSpec.builder()
                .assetCode("BTC")
                .assetId(9123)
                .scale(8)
                .active(true) // TODO set to INACTIVE
                .build();

        if (!gatewayState.registerNewAsset(specBTC)) {
            // done
        }

        final GatewayAssetSpec specUSDT = GatewayAssetSpec.builder()
                .assetCode("USDT")
                .assetId(3412)
                .scale(2)
                .active(true) // TODO set to INACTIVE
                .build();

        if (!gatewayState.registerNewAsset(specUSDT)) {
            // done
        }



///// ADD SYMBOL
        final BigDecimal takerFee = new BigDecimal("0.08");
        final BigDecimal makerFee = new BigDecimal("0.03");
        final BigDecimal lotSize = new BigDecimal("0.1");
        final BigDecimal stepSize = new BigDecimal("0.01");

        final BigDecimal marginBuy = new BigDecimal("0.01");
        final BigDecimal marginSell = new BigDecimal("0.01");

        RestApiAddSymbol request = new RestApiAddSymbol(
                "BTC_USDT_PERP",
                3199,
                SymbolType.FUTURES_CONTRACT,
                "BTC",
                "USDT",
                lotSize,
                stepSize,
                takerFee,
                makerFee,
                marginBuy,
                marginSell,
                new BigDecimal("50000"),
                new BigDecimal("1000")
        );


        // TODO Publish through bus

        final GatewayAssetSpec baseAsset = gatewayState.getAssetSpec(request.baseAsset);
        if (baseAsset == null) {
            log.warn("UNKNOWN_BASE_ASSET : " + request.baseAsset);
            return RestControllerHelper.errorResponse(ApiErrorCodes.UNKNOWN_BASE_ASSET);
        }

        final GatewayAssetSpec quoteCurrency = gatewayState.getAssetSpec(request.quoteCurrency);
        if (quoteCurrency == null) {
            log.warn("UNKNOWN_QUOTE_CURRENCY : " + request.quoteCurrency);
            return RestControllerHelper.errorResponse(ApiErrorCodes.UNKNOWN_QUOTE_CURRENCY);
        }

        // TODO validations
        final int symbolId = request.symbolId;

        // lot size in base asset units
        final BigDecimal lotSizeInBaseAssetUnits = request.lotSize.scaleByPowerOfTen(baseAsset.scale);
        if (!ArithmeticHelper.isIntegerPositiveNotZeroValue(lotSizeInBaseAssetUnits)) {
            return RestControllerHelper.errorResponse(
                    ApiErrorCodes.INVALID_CONFIGURATION,
                    "lot size must be integer and positive when converted to base asset units: " + lotSizeInBaseAssetUnits);
        }

        // step size in quote currency units
        final BigDecimal stepSizeInQuoteCurrencyUnits = request.stepSize.scaleByPowerOfTen(quoteCurrency.scale);
        if (!ArithmeticHelper.isIntegerPositiveNotZeroValue(stepSizeInQuoteCurrencyUnits)) {
            return RestControllerHelper.errorResponse(
                    ApiErrorCodes.INVALID_CONFIGURATION,
                    "step size must be integer and positive when converted to quote currency units: " + stepSizeInQuoteCurrencyUnits);
        }

        // taker fee in quote currency units
        final BigDecimal takerFeeInQuoteCurrencyUnits = request.takerFee.scaleByPowerOfTen(quoteCurrency.scale);
        if (!ArithmeticHelper.isIntegerNotNegativeValue(takerFeeInQuoteCurrencyUnits)) {
            return RestControllerHelper.errorResponse(
                    ApiErrorCodes.INVALID_CONFIGURATION,
                    "taker fee must be integer and not negative when converted to quote currency units: " + takerFeeInQuoteCurrencyUnits);
        }

        // maker fee in quote currency units
        final BigDecimal makerFeeInQuoteCurrencyUnits = request.makerFee.scaleByPowerOfTen(quoteCurrency.scale);
        if (!ArithmeticHelper.isIntegerNotNegativeValue(makerFeeInQuoteCurrencyUnits)) {
            return RestControllerHelper.errorResponse(
                    ApiErrorCodes.INVALID_CONFIGURATION,
                    "maker fee must be integer and not negative when converted to quote currency units: " + makerFeeInQuoteCurrencyUnits);
        }

        // maker/taker fee invariant validation
        if (takerFeeInQuoteCurrencyUnits.longValue() < makerFeeInQuoteCurrencyUnits.longValue()) {
            return RestControllerHelper.errorResponse(
                    ApiErrorCodes.INVALID_CONFIGURATION,
                    "taker fee " + takerFeeInQuoteCurrencyUnits + " can not be less than maker fee " + makerFeeInQuoteCurrencyUnits);
        }

        // margin buy in quote currency units
        final BigDecimal marginBuyInQuoteCurrencyUnits = request.marginBuy.scaleByPowerOfTen(quoteCurrency.scale);
        final BigDecimal marginSellInQuoteCurrencyUnits = request.marginSell.scaleByPowerOfTen(quoteCurrency.scale);

        log.debug("MARGIN {} {}", marginBuyInQuoteCurrencyUnits, marginSellInQuoteCurrencyUnits);
        if (request.symbolType == SymbolType.CURRENCY_EXCHANGE_PAIR) {
            // margin must be zero in exchange mode
            if (!ArithmeticHelper.isZero(marginBuyInQuoteCurrencyUnits) || !ArithmeticHelper.isZero(marginSellInQuoteCurrencyUnits)) {
                return RestControllerHelper.errorResponse(ApiErrorCodes.INVALID_CONFIGURATION, "margin must be zero in exchange mode");
            }

        } else {

            if (!ArithmeticHelper.isIntegerPositiveNotZeroValue(marginBuyInQuoteCurrencyUnits)) {
                return RestControllerHelper.errorResponse(
                        ApiErrorCodes.INVALID_CONFIGURATION,
                        "buy margin must be integer and positive when converted to quote currency units: " + marginBuyInQuoteCurrencyUnits);
            }
            if (!ArithmeticHelper.isIntegerPositiveNotZeroValue(marginSellInQuoteCurrencyUnits)) {
                return RestControllerHelper.errorResponse(
                        ApiErrorCodes.INVALID_CONFIGURATION,
                        "sell margin must be integer and positive when converted to quote currency units: " + marginSellInQuoteCurrencyUnits);
            }
        }

        final GatewaySymbolSpec spec = GatewaySymbolSpec.builder()
                .symbolId(symbolId)
                .symbolCode(request.symbolCode)
                .symbolType(request.symbolType)
                .baseAsset(baseAsset)
                .quoteCurrency(quoteCurrency)
                .lotSize(request.lotSize)
                .stepSize(request.stepSize)
                .takerFee(request.takerFee)
                .makerFee(request.makerFee)
                .marginBuy(request.marginBuy)
                .marginSell(request.marginSell)
                .priceHighLimit(request.priceHighLimit)
                .priceLowLimit(request.priceLowLimit)
                .status(GatewaySymbolSpec.GatewaySymbolLifecycle.NEW)
                .build();

        if (!gatewayState.registerNewSymbol(spec)) {
            log.warn("SYMBOL_ALREADY_EXISTS : id={} code={}", symbolId, request.symbolCode);
            return RestControllerHelper.errorResponse(ApiErrorCodes.SYMBOL_ALREADY_EXISTS);
        }

        final CoreSymbolSpecification coreSpec = CoreSymbolSpecification.builder()
                .symbolId(symbolId)
                .type(request.symbolType)
                .baseCurrency(baseAsset.assetId)
                .quoteCurrency(quoteCurrency.assetId)
                .baseScaleK(lotSizeInBaseAssetUnits.longValue())
                .quoteScaleK(stepSizeInQuoteCurrencyUnits.longValue())
                .takerFee(takerFeeInQuoteCurrencyUnits.longValue())
                .makerFee(makerFeeInQuoteCurrencyUnits.longValue())
                .marginBuy(marginBuyInQuoteCurrencyUnits.longValue())
                .marginSell(marginSellInQuoteCurrencyUnits.longValue())
                .build();

        log.debug("Adding symbol {}", coreSpec);

        BatchAddSymbolsCommand batchAddSymbols = new BatchAddSymbolsCommand(coreSpec);

        ExchangeApi api = exchangeCore.getApi();
        CompletableFuture<OrderCommand> future = new CompletableFuture<>();

        api.submitBinaryCommandAsync(batchAddSymbols, 123567, future::complete);

        //asyncCoreResponseCreated(asyncResponse, newSpec, cmd2.resultCode);

        OrderCommand orderCommand = future.get();

        log.info("<<< ADD SYMBOL {}", orderCommand);

        GatewaySymbolSpec newSpec = gatewayState.activateSymbol(symbolId);
        // END ADD SYMBOL







        /// ADD USER
        api.createUser(4444, future::complete);
        api.createUser(5555, future::complete);
        api.createUser(6666, future::complete);
        api.createUser(7777, future::complete);
        api.createUser(8888, future::complete);
        /// END ADDING USER









        /// ADD USER BALANCE USDT
        RestApiAccountBalanceAdjustment requestBalanceAdjustment = new RestApiAccountBalanceAdjustment(
                888,
                new BigDecimal("1000000000000"),
                "USDT"
        );
        final GatewayAssetSpec currency = gatewayState.getAssetSpec(requestBalanceAdjustment.currency);
        if (currency == null) {
            return RestControllerHelper.errorResponse(ApiErrorCodes.UNKNOWN_CURRENCY);
        }


        final BigDecimal amount = requestBalanceAdjustment.getAmount().scaleByPowerOfTen(currency.scale).stripTrailingZeros();
        if (amount.scale() > 0) {
            return RestControllerHelper.errorResponse(ApiErrorCodes.PRECISION_IS_TOO_HIGH);
        }

        final long longAmount = amount.longValue();

        api.balanceAdjustment(4444, requestBalanceAdjustment.getTransactionId(), currency.assetId, longAmount, BalanceAdjustmentType.ADJUSTMENT, future::complete);
        /// END USER BALANCE USDT










        /// ADD USER BALANCE USDT #2
        RestApiAccountBalanceAdjustment requestBalanceAdjustment2 = new RestApiAccountBalanceAdjustment(
                999,
                new BigDecimal("1000000000000"),
                "USDT"
        );
        final GatewayAssetSpec currency2 = gatewayState.getAssetSpec(requestBalanceAdjustment2.currency);
        if (currency2 == null) {
            return RestControllerHelper.errorResponse(ApiErrorCodes.UNKNOWN_CURRENCY);
        }


        final BigDecimal amount2 = requestBalanceAdjustment2.getAmount().scaleByPowerOfTen(currency2.scale).stripTrailingZeros();
        if (amount2.scale() > 0) {
            return RestControllerHelper.errorResponse(ApiErrorCodes.PRECISION_IS_TOO_HIGH);
        }

        final long longAmount2 = amount2.longValue();

        api.balanceAdjustment(5555, requestBalanceAdjustment2.getTransactionId(), currency2.assetId, longAmount2, BalanceAdjustmentType.ADJUSTMENT, future::complete);
        /// END USER BALANCE USDT #2






        /// ADD USER BALANCE USDT #3
        RestApiAccountBalanceAdjustment requestBalanceAdjustment3 = new RestApiAccountBalanceAdjustment(
                10000,
                new BigDecimal("1000000000000"),
                "USDT"
        );
        final GatewayAssetSpec currency3 = gatewayState.getAssetSpec(requestBalanceAdjustment3.currency);
        if (currency3 == null) {
            return RestControllerHelper.errorResponse(ApiErrorCodes.UNKNOWN_CURRENCY);
        }


        final BigDecimal amount3 = requestBalanceAdjustment3.getAmount().scaleByPowerOfTen(currency3.scale).stripTrailingZeros();
        if (amount3.scale() > 0) {
            return RestControllerHelper.errorResponse(ApiErrorCodes.PRECISION_IS_TOO_HIGH);
        }

        final long longAmount3 = amount3.longValue();

        api.balanceAdjustment(6666, requestBalanceAdjustment3.getTransactionId(), currency3.assetId, longAmount3, BalanceAdjustmentType.ADJUSTMENT, future::complete);
        /// END USER BALANCE USDT #3










        return RestControllerHelper.successResponse("done!", HttpStatus.CREATED);
    }

    @RequestMapping(value = "assets", method = RequestMethod.POST)
    public ResponseEntity<RestGenericResponse> createAsset(@RequestBody RestApiAdminAsset request) throws ExecutionException, InterruptedException {

        log.info(">>> ADD ASSET {}", request);

        // TODO Publish through bus
        final GatewayAssetSpec spec = GatewayAssetSpec.builder()
                .assetCode(request.assetCode)
                .assetId(request.assetId)
                .scale(request.scale)
                .active(true) // TODO set to INACTIVE
                .build();

        if (!gatewayState.registerNewAsset(spec)) {
            log.warn("Can not add asset, already exists");
            return RestControllerHelper.errorResponse(ApiErrorCodes.ASSET_ALREADY_EXISTS);
        } else {
            return RestControllerHelper.successResponse(request, HttpStatus.CREATED);
        }
    }

    @RequestMapping(value = "symbols", method = RequestMethod.POST)
    public ResponseEntity<RestGenericResponse> createSymbol(@RequestBody RestApiAddSymbol request) throws ExecutionException, InterruptedException {

        log.info("ADD SYMBOL >>> {}", request);

        // TODO Publish through bus

        final GatewayAssetSpec baseAsset = gatewayState.getAssetSpec(request.baseAsset);
        if (baseAsset == null) {
            log.warn("UNKNOWN_BASE_ASSET : " + request.baseAsset);
            return RestControllerHelper.errorResponse(ApiErrorCodes.UNKNOWN_BASE_ASSET);
        }

        final GatewayAssetSpec quoteCurrency = gatewayState.getAssetSpec(request.quoteCurrency);
        if (quoteCurrency == null) {
            log.warn("UNKNOWN_QUOTE_CURRENCY : " + request.quoteCurrency);
            return RestControllerHelper.errorResponse(ApiErrorCodes.UNKNOWN_QUOTE_CURRENCY);
        }

        // TODO validations
        final int symbolId = request.symbolId;

        // lot size in base asset units
        final BigDecimal lotSizeInBaseAssetUnits = request.lotSize.scaleByPowerOfTen(baseAsset.scale);
        if (!ArithmeticHelper.isIntegerPositiveNotZeroValue(lotSizeInBaseAssetUnits)) {
            return RestControllerHelper.errorResponse(
                    ApiErrorCodes.INVALID_CONFIGURATION,
                    "lot size must be integer and positive when converted to base asset units: " + lotSizeInBaseAssetUnits);
        }

        // step size in quote currency units
        final BigDecimal stepSizeInQuoteCurrencyUnits = request.stepSize.scaleByPowerOfTen(quoteCurrency.scale);
        if (!ArithmeticHelper.isIntegerPositiveNotZeroValue(stepSizeInQuoteCurrencyUnits)) {
            return RestControllerHelper.errorResponse(
                    ApiErrorCodes.INVALID_CONFIGURATION,
                    "step size must be integer and positive when converted to quote currency units: " + stepSizeInQuoteCurrencyUnits);
        }

        // taker fee in quote currency units
        final BigDecimal takerFeeInQuoteCurrencyUnits = request.takerFee.scaleByPowerOfTen(quoteCurrency.scale);
        if (!ArithmeticHelper.isIntegerNotNegativeValue(takerFeeInQuoteCurrencyUnits)) {
            return RestControllerHelper.errorResponse(
                    ApiErrorCodes.INVALID_CONFIGURATION,
                    "taker fee must be integer and not negative when converted to quote currency units: " + takerFeeInQuoteCurrencyUnits);
        }

        // maker fee in quote currency units
        final BigDecimal makerFeeInQuoteCurrencyUnits = request.makerFee.scaleByPowerOfTen(quoteCurrency.scale);
        if (!ArithmeticHelper.isIntegerNotNegativeValue(makerFeeInQuoteCurrencyUnits)) {
            return RestControllerHelper.errorResponse(
                    ApiErrorCodes.INVALID_CONFIGURATION,
                    "maker fee must be integer and not negative when converted to quote currency units: " + makerFeeInQuoteCurrencyUnits);
        }

        // maker/taker fee invariant validation
        if (takerFeeInQuoteCurrencyUnits.longValue() < makerFeeInQuoteCurrencyUnits.longValue()) {
            return RestControllerHelper.errorResponse(
                    ApiErrorCodes.INVALID_CONFIGURATION,
                    "taker fee " + takerFeeInQuoteCurrencyUnits + " can not be less than maker fee " + makerFeeInQuoteCurrencyUnits);
        }

        // margin buy in quote currency units
        final BigDecimal marginBuyInQuoteCurrencyUnits = request.marginBuy.scaleByPowerOfTen(quoteCurrency.scale);
        final BigDecimal marginSellInQuoteCurrencyUnits = request.marginSell.scaleByPowerOfTen(quoteCurrency.scale);

        log.debug("MARGIN {} {}", marginBuyInQuoteCurrencyUnits, marginSellInQuoteCurrencyUnits);
        if (request.symbolType == SymbolType.CURRENCY_EXCHANGE_PAIR) {
            // margin must be zero in exchange mode
            if (!ArithmeticHelper.isZero(marginBuyInQuoteCurrencyUnits) || !ArithmeticHelper.isZero(marginSellInQuoteCurrencyUnits)) {
                return RestControllerHelper.errorResponse(ApiErrorCodes.INVALID_CONFIGURATION, "margin must be zero in exchange mode");
            }

        } else {

            if (!ArithmeticHelper.isIntegerPositiveNotZeroValue(marginBuyInQuoteCurrencyUnits)) {
                return RestControllerHelper.errorResponse(
                        ApiErrorCodes.INVALID_CONFIGURATION,
                        "buy margin must be integer and positive when converted to quote currency units: " + marginBuyInQuoteCurrencyUnits);
            }
            if (!ArithmeticHelper.isIntegerPositiveNotZeroValue(marginSellInQuoteCurrencyUnits)) {
                return RestControllerHelper.errorResponse(
                        ApiErrorCodes.INVALID_CONFIGURATION,
                        "sell margin must be integer and positive when converted to quote currency units: " + marginSellInQuoteCurrencyUnits);
            }
        }

        final GatewaySymbolSpec spec = GatewaySymbolSpec.builder()
                .symbolId(symbolId)
                .symbolCode(request.symbolCode)
                .symbolType(request.symbolType)
                .baseAsset(baseAsset)
                .quoteCurrency(quoteCurrency)
                .lotSize(request.lotSize)
                .stepSize(request.stepSize)
                .takerFee(request.takerFee)
                .makerFee(request.makerFee)
                .marginBuy(request.marginBuy)
                .marginSell(request.marginSell)
                .priceHighLimit(request.priceHighLimit)
                .priceLowLimit(request.priceLowLimit)
                .status(GatewaySymbolSpec.GatewaySymbolLifecycle.NEW)
                .build();

        if (!gatewayState.registerNewSymbol(spec)) {
            log.warn("SYMBOL_ALREADY_EXISTS : id={} code={}", symbolId, request.symbolCode);
            return RestControllerHelper.errorResponse(ApiErrorCodes.SYMBOL_ALREADY_EXISTS);
        }

        final CoreSymbolSpecification coreSpec = CoreSymbolSpecification.builder()
                .symbolId(symbolId)
                .type(request.symbolType)
                .baseCurrency(baseAsset.assetId)
                .quoteCurrency(quoteCurrency.assetId)
                .baseScaleK(lotSizeInBaseAssetUnits.longValue())
                .quoteScaleK(stepSizeInQuoteCurrencyUnits.longValue())
                .takerFee(takerFeeInQuoteCurrencyUnits.longValue())
                .makerFee(makerFeeInQuoteCurrencyUnits.longValue())
                .marginBuy(marginBuyInQuoteCurrencyUnits.longValue())
                .marginSell(marginSellInQuoteCurrencyUnits.longValue())
                .build();

        log.debug("Adding symbol {}", coreSpec);

        BatchAddSymbolsCommand batchAddSymbols = new BatchAddSymbolsCommand(coreSpec);

        ExchangeApi api = exchangeCore.getApi();
        CompletableFuture<OrderCommand> future = new CompletableFuture<>();

        api.submitBinaryCommandAsync(batchAddSymbols, 123567, future::complete);

        //asyncCoreResponseCreated(asyncResponse, newSpec, cmd2.resultCode);

        OrderCommand orderCommand = future.get();

        log.info("<<< ADD SYMBOL {}", orderCommand);

        GatewaySymbolSpec newSpec = gatewayState.activateSymbol(symbolId);

        return RestControllerHelper.coreResponse(orderCommand, () -> newSpec, HttpStatus.CREATED);

    }


}
