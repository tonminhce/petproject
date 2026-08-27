package com.shop.common.spring.mapping;

import org.modelmapper.spi.ValueReader;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * {@link ValueReader} that exposes Java record components as source properties.
 *
 * <p>ModelMapper 3.2.6 with {@code MatchingStrategies.STRICT} and
 * {@code setFieldMatchingEnabled(true)} does not see record components as
 * source properties (records' canonical accessors like {@code title()} are not
 * JavaBean-style {@code getTitle()}, and the field-matching SPI doesn't read
 * them). Registering this reader ahead of the default chain restores
 * record-to-entity mapping for every service that uses records as request DTOs.
 *
 * <p>Wired by {@code ModelMapperAutoConfiguration#modelMapper} via
 * {@link org.modelmapper.Configuration#addValueReader}. Only registered when
 * the source object is a {@link Record} — falls through to the default readers
 * for everything else.
 */
public class RecordValueReader implements ValueReader<Record> {

    @Override
    public Object get(Record source, String memberName) {
        if (source == null) {
            return null;
        }
        try {
            Method accessor = source.getClass().getMethod(memberName);
            return accessor.invoke(source);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            return null;
        }
    }

    @Override
    public ValueReader.Member<Record> getMember(Record source, String memberName) {
        Object value = get(source, memberName);
        return value == null ? null : new ValueReader.Member<>(value.getClass()) {
            @Override
            public Object get(Record record, String name) {
                return RecordValueReader.this.get(record, name);
            }
        };
    }

    @Override
    public Collection<String> memberNames(Record source) {
        if (source == null) {
            return Collections.emptySet();
        }
        Set<String> names = new HashSet<>();
        for (RecordComponent component : source.getClass().getRecordComponents()) {
            names.add(component.getName());
        }
        return names;
    }
}
