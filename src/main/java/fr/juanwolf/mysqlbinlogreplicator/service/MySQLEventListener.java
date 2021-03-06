/*
    Copyright (C) 2015  Jean-Loup Adde

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package fr.juanwolf.mysqlbinlogreplicator.service;

import com.github.shyiko.mysql.binlog.BinaryLogClient;
import com.github.shyiko.mysql.binlog.event.*;
import com.github.shyiko.mysql.binlog.event.deserialization.ColumnType;
import fr.juanwolf.mysqlbinlogreplicator.DomainClass;
import fr.juanwolf.mysqlbinlogreplicator.annotations.NestedMapping;
import fr.juanwolf.mysqlbinlogreplicator.component.DomainClassAnalyzer;
import fr.juanwolf.mysqlbinlogreplicator.nested.SQLRelationship;
import fr.juanwolf.mysqlbinlogreplicator.nested.requester.SQLRequester;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.repository.CrudRepository;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.text.ParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class MySQLEventListener implements BinaryLogClient.EventListener {

    //All columnNames for all tables
    @Setter
    private Map<String, Object[]> columnMap;
    // All the column type for the table
    @Getter
    @Setter
    private Map<String, byte[]> columnsTypes;
    //Current tableName
    @Getter
    @Setter(AccessLevel.PROTECTED)
    private String tableName;

    @Getter
    private DomainClassAnalyzer domainClassAnalyzer;

    public MySQLEventListener(Map<String, Object[]> columnMap, DomainClassAnalyzer domainClassAnalyzer) {
        this.columnMap = columnMap;
        this.domainClassAnalyzer = domainClassAnalyzer;
        this.columnsTypes =  new HashMap<>();
    }

    @Override
    public void onEvent(Event event) {
        try {
            this.actionOnEvent(event);
        } catch (Exception e) {
            log.error("An exception occurred during OnEvent.", e);
        }
    }

    public void actionOnEvent(Event event) throws Exception {
        if (tableName != null && isMappingConcern()) {
            DomainClass domainClass = domainClassAnalyzer.getDomainClassMap().get(tableName);
            CrudRepository currentRepository = domainClass.getCrudRepository();
            Class currentClass = domainClass.getDomainClass();
            if (EventType.isDelete(event.getHeader().getEventType())) {
                Object domainObject = currentClass.cast(generateDomainObjectForDeleteEvent(event, tableName));
                currentRepository.delete(domainObject);
                log.debug("Object deleted : {}", domainObject.toString());
            } else if (EventType.isUpdate(event.getHeader().getEventType())) {
                UpdateRowsEventData data = event.getData();
                log.debug("Update event received data = {}", data);
                Object domainObject = currentClass.cast(generateDomainObjectForUpdateEvent(event, tableName));
                currentRepository.save(domainObject);
            } else if (EventType.isWrite(event.getHeader().getEventType())) {
                WriteRowsEventData data = event.getData();
                log.debug("Write event received with data = {}", data);
                Object currentClassInstance = currentClass.cast(generateDomainObjectForWriteEvent(event, tableName));
                currentRepository.save(currentClassInstance);
            }
        }
        if (tableName != null && isNestedConcern() && isCrudEvent(event.getHeader().getEventType())) {
            DomainClass domainClass = domainClassAnalyzer.getNestedDomainClassMap().get(tableName);
            for (SQLRequester sqlRequester : domainClass.getSqlRequesters().values()) {
                if (sqlRequester.getExitTableName().equals(tableName)) {
                    String primaryKeyValue = getPrimaryKeyFromEvent(event, sqlRequester, tableName);
                    Object mainObject = sqlRequester.reverseQueryEntity(sqlRequester.getForeignKey(),
                            sqlRequester.getPrimaryKeyForeignEntity(), primaryKeyValue);
                    CrudRepository crudRepository = domainClass.getCrudRepository();
                    if (mainObject instanceof List) {
                        List<Object> mainObjectList = (List<Object>) mainObject;
                        for (Object object : mainObjectList) {
                            crudRepository.save(object);
                        }
                    } else {
                        crudRepository.save(mainObject);
                    }
                }
            }
        }
        if (event.getHeader().getEventType() == EventType.TABLE_MAP) {
            TableMapEventData tableMapEventData = event.getData();
            tableName = tableMapEventData.getTable();
            if (!columnsTypes.containsKey(tableName)) {
                columnsTypes.put(tableName, tableMapEventData.getColumnTypes());
            }
        }
    }

    boolean isMappingConcern() {
        return domainClassAnalyzer.getMappingTablesExpected().contains(tableName);
    }

    boolean isNestedConcern() {
        return domainClassAnalyzer.getNestedTables().contains(tableName);
    }

    boolean isCrudEvent(EventType event) {
        return EventType.isDelete(event) || EventType.isUpdate(event) || EventType.isWrite(event);
    }

    Object generateDomainObjectForUpdateEvent(Event event, String tableName) throws ReflectiveOperationException, ParseException {
        UpdateRowsEventData data = event.getData();
        Serializable[] afterValues = data.getRows().get(0).getValue();
        return getObjectFromRows(afterValues, tableName);
    }

    Object generateDomainObjectForWriteEvent(Event event, String tableName) throws ReflectiveOperationException, ParseException {
        WriteRowsEventData data = event.getData();
        Serializable[] rows = data.getRows().get(0);
        return getObjectFromRows(rows, tableName);
    }

    Object generateDomainObjectForDeleteEvent(Event event, String tableName) throws ReflectiveOperationException, ParseException {
        DeleteRowsEventData data = event.getData();
        Serializable[] rows = data.getRows().get(0);
        return getObjectFromRows(rows, tableName);
    }

    Object getObjectFromRows(Serializable[] rows, String tableName) throws ReflectiveOperationException, ParseException {
        Object[] columns = columnMap.get(tableName);
        Object object = domainClassAnalyzer.generateInstanceFromName(tableName);
        DomainClass domainClass = domainClassAnalyzer.getDomainClassMap().get(tableName);

        // Setting up the list of foreign keys
        Map<String, SQLRequester> foreignKeysSQLRequesterMap = new HashMap();
        for (String nestedField: domainClass.getSqlRequesters().keySet()) {
            SQLRequester sqlRequester = domainClass.getSqlRequesters().get(nestedField);
            foreignKeysSQLRequesterMap.put(sqlRequester.getForeignKey(), sqlRequester);
        }

        String debugLogObject = "";
        byte[] columnsType = columnsTypes.get(tableName);
        for (int i = 0; i < rows.length; i++) {
            if (rows[i] != null) {
                try {
                    Field field;
                    if (foreignKeysSQLRequesterMap.keySet().contains(columns[i].toString())) {
                        String columnsName = columns[i].toString();
                        field = foreignKeysSQLRequesterMap.get(columnsName).getAssociatedField();
                    } else {
                        field = object.getClass().getDeclaredField(columns[i].toString());
                    }
                    domainClassAnalyzer.instantiateField(object, field, rows[i].toString(), columnsType[i], tableName);
                    if (log.isDebugEnabled()) {
                        debugLogObject += columns[i] + "=" + rows[i].toString() + ", ";
                    }
                } catch (NoSuchFieldException exception) {
                    log.warn("No field found for {}", columns[i].toString());
                }
            }
        }
        for (Field field : object.getClass().getDeclaredFields()) {
            field.setAccessible(true);
            Object fieldValue = field.get(object);
            if (fieldValue == null) {
                if (field.isAnnotationPresent(NestedMapping.class)) {
                    NestedMapping nestedMapping = field.getAnnotation(NestedMapping.class);
                    if (nestedMapping.sqlAssociaton() == SQLRelationship.ONE_TO_MANY) {
                        Field primaryKeyField = object.getClass().getField(nestedMapping.primaryKey());
                        primaryKeyField.setAccessible(true);
                        Object primaryKeyValue = primaryKeyField.get(object);
                        domainClassAnalyzer.instantiateField(object, field, primaryKeyValue.toString(),
                                ColumnType.STRING.getCode(), tableName);
                        primaryKeyField.setAccessible(false);
                    }
                }
            }
            field.setAccessible(false);
        }
        log.debug("Object generated :  {{}}", debugLogObject);
        return object;
    }

    String getPrimaryKeyFromEvent(Event event, SQLRequester sqlRequester, String tableName) {
        Serializable[] rows = null;
        if (EventType.isDelete(event.getHeader().getEventType())) {
            DeleteRowsEventData data = event.getData();
            rows = data.getRows().get(0);
        } else if (EventType.isUpdate(event.getHeader().getEventType())){
            UpdateRowsEventData data = event.getData();
            rows = data.getRows().get(0).getValue();
        } else if (EventType.isWrite(event.getHeader().getEventType())) {
            WriteRowsEventData data = event.getData();
            rows = data.getRows().get(0);
        }
        return getPrimaryKeyValueFromRows(rows, tableName, sqlRequester);
    }

    String getPrimaryKeyValueFromRows(Serializable[] rows, String tableName, SQLRequester sqlRequester) {
        Object[] columns = columnMap.get(tableName);
        for (int i = 0; i < rows.length; i++) {
            if (rows[i] != null) {
                if (sqlRequester.getPrimaryKeyForeignEntity().equals(columns[i])) {
                    return rows[i].toString();
                }
            }

        }
        return null;
    }

}
