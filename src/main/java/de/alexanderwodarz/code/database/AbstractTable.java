package de.alexanderwodarz.code.database;

import de.alexanderwodarz.code.database.annotation.Column;
import de.alexanderwodarz.code.database.annotation.Table;
import de.alexanderwodarz.code.database.enums.ColumnDefault;
import de.alexanderwodarz.code.database.enums.ColumnType;
import de.alexanderwodarz.code.database.enums.DataType;
import de.alexanderwodarz.code.database.enums.RowSort;
import de.alexanderwodarz.code.database.pagination.Pagination;
import de.alexanderwodarz.code.database.query.QuerySelector;
import de.alexanderwodarz.code.database.update.UpdateSelector;
import lombok.SneakyThrows;

import java.lang.reflect.Field;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.util.ArrayList;
import java.util.List;

public abstract class AbstractTable<T extends AbstractTable> {

    private Database database;

    public AbstractTable(Database database) {
        this.database = database;
    }

    public T setValues(ResultSet rs) {
        try {
            T entry = (T) this.getClass().getConstructor(Database.class).newInstance(getDatabase());
            for (Field field : entry.getClass().getFields()) {
                if (!field.isAnnotationPresent(Column.class))
                    continue;
                Column column = field.getAnnotation(Column.class);
                setFieldValue(field, entry, rs.getObject(column.name().length() == 0 ? field.getName() : column.name()));
            }
            return entry;
        } catch (Exception e) {
            return null;
        }
    }

    public Database getDatabase() {
        return database;
    }

    public void check() {

    }

    @SneakyThrows
    public List<T> getAll(T filter, String sortColumn, RowSort sort, int limit, boolean verbose) {
        List<T> list = new ArrayList<>();
        String query = generateQuery(filter.getClass().getFields(), limit, sortColumn, sort, filter);
        if (verbose)
            System.out.println(query);
        try {
            ResultSet rs = database.query(query);
            if (rs.next()) {
                while (!rs.isAfterLast()) {
                    T entry = (T) filter.getClass().getConstructor(Database.class).newInstance(database);
                    for (Field field : entry.getClass().getFields()) {
                        if (!field.isAnnotationPresent(Column.class))
                            continue;
                        Column column = field.getAnnotation(Column.class);
                        setFieldValue(field, entry, rs.getObject(column.name().length() == 0 ? field.getName() : column.name()));
                    }
                    list.add(entry);
                    rs.next();
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }

    public QuerySelector<T> query(boolean verbose) {
        return new QuerySelector<>((T) this, verbose);
    }

    public QuerySelector<T> query() {
        return query(database.isVerbose());
    }


    private String generateQuery(Field[] fields, int limit, String sortType, RowSort sort, T t) {
        if (!(t instanceof AbstractTable))
            return "";
        AbstractTable table = (AbstractTable) t;
        String query = "SELECT * FROM " + table.getName();
        String wheres = "";
        for (Field field : fields) {
            try {
                Column column = field.getAnnotation(Column.class);
                if (field.get(table) instanceof Integer) {
                    if (field.getInt(table) > 0) {
                        wheres += "`" + (column.name().length() == 0 ? field.getName() : column.name()) + "`='" + field.get(table) + "' AND ";
                    }
                }
                if (field.get(table) instanceof String) {
                    if (field.get(table).toString() != null) {
                        wheres += "`" + (column.name().length() == 0 ? field.getName() : column.name()) + "`='" + field.get(table) + "' AND ";
                    }
                }
                if (field.get(table) instanceof Long) {
                    if (field.getLong(table) > 0) {
                        wheres += "`" + (column.name().length() == 0 ? field.getName() : column.name()) + "`='" + field.get(table) + "' AND ";
                    }
                }
                if (field.get(table) instanceof Boolean) {
                    if (column.defaultValue() == ColumnDefault.BOOLEAN && column.defaultBoolean() == field.getBoolean(table))
                        continue;
                    wheres += "`" + (column.name().length() == 0 ? field.getName() : column.name()) + "`='" + (field.getBoolean(table) ? "1" : "0") + "' AND ";
                }
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            }
        }
        if (wheres.length() > 0)
            query += " WHERE " + wheres.substring(0, wheres.length() - 5);
        if (sort != null)
            query += " ORDER BY " + sortType + " " + sort;
        if (limit > 0)
            query += " LIMIT " + limit;
        return query;
    }

    public void create() {
        create(null, false);
    }

    public void create(boolean verbose) {
        create(null, verbose);
    }

    public void create(Runnable function) {
        create(function, false);
    }

    @SneakyThrows
    public void create(Runnable function, boolean verbose) {
        String name = getName();
        StringBuilder query = new StringBuilder("CREATE TABLE IF NOT EXISTS " + name + " (");
        for (Field field : this.getClass().getFields()) {
            query.append(getFieldCreation(field));
        }
        query = new StringBuilder(query.substring(0, query.length() - 1));
        if (database.getType().equals("mysql")) {
            if (hasPrimaryKey()) {
                query.append(", primary key (");
                for (Field field : this.getClass().getFields()) {
                    if (!field.isAnnotationPresent(Column.class))
                        continue;
                    if (!field.getAnnotation(Column.class).primaryKey())
                        continue;
                    query.append(getColumnNameFromField(field)).append(",");
                }
                query = new StringBuilder(query.substring(0, query.length() - 1) + ")");
            }
        }
        if (hasForeignKey()) {
            for (Field field : this.getClass().getFields()) {
                if (!field.isAnnotationPresent(Column.class))
                    continue;
                if (field.getAnnotation(Column.class).foreignKey().length() == 0)
                    continue;
                query.append(getColumnForeignKey(field));
            }
        }
        query.append(")");
        if (verbose)
            System.out.println(query);
        boolean added = false;
        if (database.getType().equals("sqlite"))
            added = !(database.query("SELECT * FROM sqlite_master WHERE type='table' AND tbl_name='" + getName() + "'").next());
        SQLWarning warning = database.update(query.toString(), null).getWarnings();
        if ((database.getType().equals("mysql") && warning == null) || added)
            if (function != null)
                function.run();
    }

    public String getColumnForeignKey(Field field) {
        Column column = field.getAnnotation(Column.class);
        return ", constraint " + getName() + "_" + column.foreignKeyTable() + "_" + getColumnNameFromField(field) + "_fk foreign key (" + getColumnNameFromField(field) + ") references " + column.foreignKeyTable() + " (" + column.foreignKey() + ") on update " + column.foreignKeyUpdate() + " on delete " + column.foreignKeyDelete();
    }

    public boolean hasPrimaryKey() {
        for (Field field : this.getClass().getFields()) {
            if (!field.isAnnotationPresent(Column.class))
                continue;
            if (field.getAnnotation(Column.class).primaryKey())
                return true;
        }
        return false;
    }

    public boolean hasForeignKey() {
        for (Field field : this.getClass().getFields()) {
            if (!field.isAnnotationPresent(Column.class))
                continue;
            if (field.getAnnotation(Column.class).foreignKey().length() > 0)
                return true;
        }
        return false;
    }

    public void delete() {
        delete(false);
    }

    @SneakyThrows
    public void delete(boolean verbose) {
        String delete = "DELETE FROM " + getName() + " ";
        String wheres = "";
        for (Field field : getClass().getDeclaredFields()) {
            if (!field.isAnnotationPresent(Column.class))
                continue;
            if (field.getAnnotation(Column.class).autoIncrement())
                continue;
            if (field.get(this) == null)
                continue;
            wheres += "`" + field.getName() + "`='" + field.get(this) + "' AND ";
        }
        if (wheres.length() > 0)
            delete += "WHERE " + wheres.substring(0, wheres.length() - 4);
        delete += ";";
        if (verbose)
            System.out.println(delete);
        database.update(delete, new ArrayList<>());
    }

    public int insert() {
        return insert(false);
    }

    @SneakyThrows
    public int insert(boolean verbose) {
        String name = getName();
        String insert = "INSERT INTO " + name + " ";
        String names = "";
        String value = "";
        List<Object> values = new ArrayList<>();
        for (Field field : getClass().getFields()) {
            if (field.isAnnotationPresent(Column.class)) {
                if (!field.getAnnotation(Column.class).autoIncrement()) {
                    try {
                        if (field.get(this) == null)
                            continue;
                        names += "`" + (field.getAnnotation(Column.class).name().length() > 0 ? field.getAnnotation(Column.class).name() : field.getName()) + "`, ";
                        value += "?,";
                        values.add(field.get(this));
                    } catch (IllegalAccessException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
        names = names.substring(0, names.length() - 2);
        insert += "(" + names + ") VALUES (" + value.substring(0, value.length() - 1) + ");";
        if (verbose){
            System.out.println(insert);
            for (Object o : values) {
                System.out.println(o);
            }
        }
        PreparedStatement result = database.update(insert, values);
        ResultSet set = result.getGeneratedKeys();
        set.next();
        try{
            return set.getInt(1);
        }catch (Exception e){
            return 0;
        }
    }

    public String getFieldCreation(Field field) {
        if (!field.isAnnotationPresent(Column.class))
            return "";
        Column col = field.getAnnotation(Column.class);
        String creation = "`" + getColumnNameFromField(field) + "`";
        if (database.getType().equals("mysql")) {
            creation += " " + (col.type().getName().equals(ColumnType.empty.getName()) ? DataType.getByName(field.getType().getSimpleName()).getName() : col.type().getName()) + (col.length() == 0 ? "" : "(" + col.length() + ")");
            if (col.defaultValue().getMethod().length() > 0 || col.defaultValue() == ColumnDefault.INTEGER || col.defaultValue() == ColumnDefault.BOOLEAN)
                creation += " default " + (col.defaultValue() == ColumnDefault.INTEGER ? col.defaultInt() : col.defaultValue() == ColumnDefault.BOOLEAN ? col.defaultBoolean() : col.defaultValue().getMethod());
            creation += " " + (col.autoIncrement() ? "auto_increment" : (col.primaryKey() ? "not " : "") + "null");
            creation += ",";
        } else {
            creation += " " + DataType.getByName(field.getType().getSimpleName()).getName() + (col.length() == 0 ? "" : "(" + col.length() + ")");
            creation += (col.primaryKey() || col.autoIncrement() ? " constraint " + getName() + " primary key" + (col.autoIncrement() ? " autoincrement" : "") : "");
            creation += ",";
        }
        return creation;
    }

    public String getColumnNameFromField(Field f) {
        if (!f.isAnnotationPresent(Column.class))
            return "";
        if (f.getAnnotation(Column.class).name().length() == 0)
            return f.getName();
        else
            return f.getAnnotation(Column.class).name();
    }

    public String getName() {
        Table ann = this.getClass().getAnnotation(Table.class);
        String name = "";
        if (ann.name().length() == 0)
            name = this.getClass().getSimpleName().toLowerCase();
        else
            name = ann.name();
        return "`" + name + "`";
    }

    public UpdateSelector<AbstractTable> update() {
        return new UpdateSelector<>(this);
    }

    public void update(T update, T old) {
        String generated = generateUpdateQuery(update.getClass().getDeclaredFields(), old.getClass().getDeclaredFields(), old, update);
        database.update(generated, null);
    }

    public Pagination<T> getPagination() {
        return getPagination(10, query());
    }

    public Pagination<T> getPagination(QuerySelector query) {
        return getPagination(10, query);
    }

    public Pagination<T> getPagination(int limit, QuerySelector query) {
        return new Pagination<>((T) this, limit, query);
    }

    private String generateUpdateQuery(Field[] newFields, Field[] whereFields, T t, T newT) {
        if (!(t instanceof AbstractTable))
            return "";
        AbstractTable table = (AbstractTable) t;
        AbstractTable newTable = (AbstractTable) newT;
        String query = "UPDATE " + table.getName();
        String set = "";
        String wheres = "";
        for (Field field : newFields) {
            try {
                if (field.get(newTable) instanceof Integer) {
                    if (field.getInt(newTable) > 0) {
                        set += "`" + field.getName() + "`='" + field.get(newTable) + "', ";
                    }
                }
                if (field.get(newTable) instanceof String) {
                    if (field.get(newTable).toString() != null) {
                        Column column = field.getAnnotation(Column.class);
                        set += "`" + (column.name().length() == 0 ? field.getName() : column.name()) + "`='" + field.get(newTable) + "', ";
                    }
                }
                if (field.get(newTable) instanceof Long) {
                    if (field.getLong(newTable) > 0) {
                        set += "`" + field.getName() + "`='" + field.get(newTable) + "', ";
                    }
                }
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            }
        }
        for (Field field : whereFields) {
            try {
                if (field.get(table) instanceof Integer) {
                    if (field.getInt(table) > 0) {
                        wheres += "`" + field.getName() + "`='" + field.get(table) + "' AND ";
                    }
                }
                if (field.get(table) instanceof String) {
                    if (field.get(table).toString() != null) {
                        Column column = field.getAnnotation(Column.class);
                        wheres += "`" + (column.name().length() == 0 ? field.getName() : column.name()) + "`='" + field.get(table) + "' AND ";
                    }
                }
                if (field.get(table) instanceof Long) {
                    if (field.getLong(table) > 0) {
                        wheres += "`" + field.getName() + "`='" + field.get(table) + "' AND ";
                    }
                }
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            }
        }

        if (set.length() > 0)
            query += " SET " + set.substring(0, set.length() - 2);
        if (wheres.length() > 0)
            query += " WHERE " + wheres.substring(0, wheres.length() - 5);
        System.out.println(query);
        return query;
    }

    private void setFieldValue(Field field, Object t, Object set) throws IllegalAccessException {
        if (field.getType() == String.class)
            field.set(t, set == null ? "" : set.toString());
        if (field.getType() == int.class) {
            try {
                field.set(t, set == null ? 0 : set);
            } catch (Exception e) {
                field.set(t, Boolean.parseBoolean(set + "") ? 1 : 0);
            }
        }
        if (field.getType() == boolean.class)
            field.set(t, Boolean.parseBoolean(set.toString()));
        if (field.getType() == long.class) {
            if (set == null)
                set = 0;
            field.set(t, Long.parseLong(set.toString()));
        }
    }
}
