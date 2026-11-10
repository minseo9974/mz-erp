package com.mzerp.common.money;

import java.math.BigDecimal;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Money ↔ DB NUMERIC(18,0). 소수점이 있는 값이 들어오면 데이터가 잘못된 것이므로 예외. */
@Converter(autoApply = true)
public class MoneyConverter implements AttributeConverter<Money, BigDecimal> {

    @Override
    public BigDecimal convertToDatabaseColumn(Money money) {
        return money == null ? null : BigDecimal.valueOf(money.won());
    }

    @Override
    public Money convertToEntityAttribute(BigDecimal value) {
        return value == null ? null : Money.won(value.longValueExact());
    }
}
