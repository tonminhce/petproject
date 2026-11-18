package com.shop.common.spring.mapping;

import org.modelmapper.spi.ValueReader;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

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
