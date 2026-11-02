package com.buy01.order_service.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.bson.Document;
import org.bson.types.Decimal128;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.stereotype.Repository;

import com.buy01.order_service.dtos.response.AnalyticsResponse;
import com.buy01.order_service.dtos.response.AnalyticsResponse.CategorySummary;
import com.buy01.order_service.dtos.response.AnalyticsResponse.MonthlySummary;
import com.buy01.order_service.dtos.response.AnalyticsResponse.ProductSummary;

import lombok.RequiredArgsConstructor;

/** Aggregate in MongoDB instead of loading the paginated order list into application memory. */
@Repository
@RequiredArgsConstructor
public class AnalyticsRepository {
    private static final String ORDER_COUNT = "orderCount";
    private static final String UNITS = "units";
    private static final String AMOUNT = "amount";
    private static final String ITEM_QUANTITY = "$items.quantity";
    private static final String ITEM_TOTAL = "$items.lineTotal";
    private static final String SORT = "$sort";

    private final MongoTemplate mongo;

    public AnalyticsResponse summarize(String userId, boolean seller, LocalDate from, LocalDate to) {
        List<AggregationOperation> stages = new ArrayList<>();
        Document match = new Document(seller ? "sellerIds" : "customerId", userId)
                .append("status", "DELIVERED")
                .append("createdAt", new Document("$gte", from.atStartOfDay(ZoneOffset.UTC).toInstant())
                        .append("$lt", to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()));
        stages.add(stage("$match", match));
        stages.add(stage("$unwind", "$items"));
        // Matching the parent order is insufficient: mixed-seller orders contain other sellers' lines.
        if (seller) {
            stages.add(stage("$match", new Document("items.sellerId", userId)));
        }
        stages.add(stage("$facet", new Document("totals", totals())
                .append("products", products()).append("categories", categories()).append("months", months())));
        Document result = mongo.aggregate(Aggregation.newAggregation(stages), "orders", Document.class)
                .getUniqueMappedResult();
        if (result == null) {
            result = new Document();
        }
        List<Document> totals = documents(result, "totals");
        Document total = totals.isEmpty() ? new Document() : totals.get(0);
        return new AnalyticsResponse(from, to, "DELIVERED", amount(total), number(total, ORDER_COUNT),
                number(total, UNITS), documents(result, "products").stream()
                        .map(row -> new ProductSummary(row.getString("_id"), row.getString("name"),
                                number(row, UNITS), amount(row))).toList(),
                documents(result, "categories").stream()
                        .map(row -> new CategorySummary(row.getString("_id"), number(row, UNITS), amount(row))).toList(),
                fillMonths(documents(result, "months"), from, to));
    }

    private static List<Document> totals() {
        return List.of(group("$_id", ITEM_QUANTITY, ITEM_TOTAL, new Document()),
                group(null, "$units", "$amount", new Document(ORDER_COUNT, sum(1))));
    }

    private static List<Document> products() {
        return List.of(new Document(SORT, new Document("createdAt", -1).append("_id", -1)),
                group("$items.productId", ITEM_QUANTITY, ITEM_TOTAL,
                        new Document("name", new Document("$first", "$items.productName"))),
                ranking(), new Document("$limit", 5));
    }

    private static List<Document> categories() {
        Document trimmed = new Document("$trim", new Document("input",
                new Document("$ifNull", List.of("$items.category", ""))));
        Document category = new Document("$cond", List.of(new Document("$eq", List.of(trimmed, "")),
                "uncategorized", trimmed));
        return List.of(group(category, ITEM_QUANTITY, ITEM_TOTAL, new Document()), ranking(), new Document("$limit", 5));
    }

    private static List<Document> months() {
        Document month = new Document("$dateToString", new Document("format", "%Y-%m")
                .append("date", "$createdAt").append("timezone", "UTC"));
        return List.of(group(new Document("month", month).append("order", "$_id"),
                        ITEM_QUANTITY, ITEM_TOTAL, new Document()),
                group("$_id.month", "$units", "$amount", new Document(ORDER_COUNT, sum(1))), new Document(SORT, new Document("_id", 1)));
    }

    private static Document group(Object id, String units, String amount, Document extra) {
        Document fields = new Document("_id", id).append(UNITS, sum(units)).append(AMOUNT, sum(amount));
        fields.putAll(extra);
        return new Document("$group", fields);
    }

    private static Document sum(Object expression) { return new Document("$sum", expression); }
    private static Document ranking() {
        return new Document(SORT, new Document(UNITS, -1).append(AMOUNT, -1).append("_id", 1));
    }
    private static AggregationOperation stage(String operator, Object value) {
        return context -> new Document(operator, value);
    }
    private static List<Document> documents(Document result, String key) {
        return result.getList(key, Document.class, List.of());
    }
    private static long number(Document row, String key) { return ((Number) row.getOrDefault(key, 0)).longValue(); }
    private static BigDecimal amount(Document row) {
        Object value = row.getOrDefault(AMOUNT, 0);
        return value instanceof Decimal128 decimal ? decimal.bigDecimalValue() : new BigDecimal(value.toString());
    }
    private static List<MonthlySummary> fillMonths(List<Document> rows, LocalDate from, LocalDate to) {
        List<MonthlySummary> result = new ArrayList<>();
        for (YearMonth month = YearMonth.from(from); !month.isAfter(YearMonth.from(to)); month = month.plusMonths(1)) {
            String key = month.toString();
            Document row = rows.stream().filter(item -> key.equals(item.getString("_id"))).findFirst()
                    .orElseGet(Document::new);
            result.add(new MonthlySummary(key, number(row, ORDER_COUNT), number(row, UNITS), amount(row)));
        }
        return List.copyOf(result);
    }
}
