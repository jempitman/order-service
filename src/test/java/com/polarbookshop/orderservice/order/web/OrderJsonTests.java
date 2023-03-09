package com.polarbookshop.orderservice.order.web;

import com.polarbookshop.orderservice.order.domain.Order;
import com.polarbookshop.orderservice.order.domain.OrderStatus;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.boot.test.json.JacksonTester;

import java.time.Instant;

import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

@JsonTest
public class OrderJsonTests {

    @Autowired
    private JacksonTester<Order> json;

    @Test
    void testSerialize() throws Exception {
        var order = new Order(394L, "1234567890", "Book Name", 9.90,
                1, OrderStatus.ACCEPTED, Instant.now(), Instant.now(), null,
                null, 21);
        var jsonContent = json.write(order);
        assertThat(jsonContent).extractingJsonPathNumberValue("@.id")
                .isEqualTo(order.id().intValue());
        Assertions.assertThat(jsonContent).extractingJsonPathStringValue("@.bookIsbn")
                .isEqualTo(order.bookIsbn());
        Assertions.assertThat(jsonContent).extractingJsonPathStringValue("@.bookName")
                .isEqualTo(order.bookName());
        Assertions.assertThat(jsonContent).extractingJsonPathNumberValue("@.bookPrice")
                .isEqualTo(order.bookPrice());
        Assertions.assertThat(jsonContent).extractingJsonPathNumberValue("@.quantity")
                .isEqualTo(order.quantity());
        Assertions.assertThat(jsonContent).extractingJsonPathStringValue("@.status")
                .isEqualTo(order.status().toString());
        Assertions.assertThat(jsonContent).extractingJsonPathStringValue("@.createdDate")
                .isEqualTo(order.createdDate().toString());
        Assertions.assertThat(jsonContent).extractingJsonPathStringValue("@.lastModifiedDate")
                .isEqualTo(order.lastModifiedDate().toString());
        Assertions.assertThat(jsonContent).extractingJsonPathStringValue("@.createdBy")
                .isEqualTo(order.createdBy());
        Assertions.assertThat(jsonContent).extractingJsonPathStringValue("@.lastModifiedBy")
                .isEqualTo(order.lastModifiedBy());
        Assertions.assertThat(jsonContent).extractingJsonPathNumberValue("@.version")
                .isEqualTo(order.version());



    }
}
