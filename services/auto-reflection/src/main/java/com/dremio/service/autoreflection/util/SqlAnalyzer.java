package com.dremio.service.autoreflection.util;

import com.dremio.common.utils.SqlUtils;
import com.dremio.service.autoreflection.model.QueryRecord;
import org.apache.calcite.sql.*;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * SQL解析器 - 从SQL语句中提取表、字段等信息
 * 集成 Dremio 的 SQL 解析工具
 */
public class SqlAnalyzer {

    private static final Logger logger = LoggerFactory.getLogger(SqlAnalyzer.class);

    /**
     * 从SQL中提取关键信息
     */
    public static QueryRecord extractQueryInfo(String queryId, String sql, String user) {
        QueryRecord record = new QueryRecord();
        record.setQueryId(queryId);
        record.setSql(sql);
        record.setUser(user);
        record.setStartTime(System.currentTimeMillis());
        record.setSqlNormalized(normalizeSql(sql));

        try {
            SqlNode sqlNode = parseSql(sql);
            
            // 提取表名
            List<String> tables = extractTables(sqlNode);
            record.setAllDatasets(tables);
            record.setDataset(tables.isEmpty() ? null : tables.get(0));

            // 提取查询类型
            record.setQueryType(determineQueryType(sqlNode));

            // 提取字段信息
            extractFields(sqlNode, record);

        } catch (Exception e) {
            logger.warn("Failed to parse SQL: {}, error: {}", sql, e.getMessage());
        }

        return record;
    }

    /**
     * 解析SQL
     */
    private static SqlNode parseSql(String sql) throws SqlParseException {
        SqlParser parser = SqlParser.create(sql);
        return parser.parseQuery();
    }

    /**
     * 提取表名
     */
    private static List<String> extractTables(SqlNode sqlNode) {
        List<String> tables = new ArrayList<>();
        
        if (sqlNode instanceof SqlSelect) {
            SqlSelect select = (SqlSelect) sqlNode;
            
            // 提取FROM子句中的表
            SqlNode from = select.getFrom();
            if (from != null) {
                extractTableFromNode(from, tables);
            }

            // 处理UNION
            if (select.getUnion() != null) {
                extractTables(select.getUnion(), tables);
            }
        } else if (sqlNode instanceof SqlUnion) {
            extractTables((SqlUnion) sqlNode, tables);
        }

        return tables;
    }

    private static void extractTableFromNode(SqlNode node, List<String> tables) {
        if (node instanceof SqlIdentifier) {
            tables.add(node.toString());
        } else if (node instanceof SqlJoin) {
            SqlJoin join = (SqlJoin) node;
            extractTableFromNode(join.getLeft(), tables);
            extractTableFromNode(join.getRight(), tables);
        } else if (node instanceof SqlSelect) {
            extractTables((SqlSelect) node, tables);
        }
    }

    private static void extractTables(SqlUnion union, List<String> tables) {
        if (union.getLeft() != null) {
            extractTables(union.getLeft(), tables);
        }
        if (union.getRight() != null) {
            extractTables(union.getRight(), tables);
        }
    }

    /**
     * 提取字段信息
     */
    private static void extractFields(SqlNode sqlNode, QueryRecord record) {
        if (!(sqlNode instanceof SqlSelect)) {
            return;
        }

        SqlSelect select = (SqlSelect) sqlNode;

        // SELECT 字段
        List<String> selectFields = new ArrayList<>();
        SqlNodeList selectList = select.getSelectList();
        if (selectList != null) {
            for (SqlNode node : selectList) {
                selectFields.add(node.toString());
            }
        }
        record.setSelectFields(selectFields);

        // WHERE 字段
        List<String> filterFields = new ArrayList<>();
        SqlNode where = select.getWhere();
        if (where != null) {
            extractFieldsFromExpression(where, filterFields);
        }
        record.setFilterFields(filterFields);

        // GROUP BY 字段
        List<String> groupByFields = new ArrayList<>();
        SqlNodeList groupBy = select.getGroup();
        if (groupBy != null) {
            for (SqlNode node : groupBy) {
                groupByFields.add(node.toString());
            }
        }
        record.setGroupByFields(groupByFields);

        // ORDER BY 字段
        List<String> orderByFields = new ArrayList<>();
        SqlNodeList orderBy = select.getOrder();
        if (orderBy != null) {
            for (SqlNode node : orderBy) {
                orderByFields.add(node.toString());
            }
        }
        record.setOrderByFields(orderByFields);
    }

    private static void extractFieldsFromExpression(SqlNode node, List<String> fields) {
        if (node == null) {
            return;
        }

        if (node instanceof SqlIdentifier) {
            String field = node.toString();
            if (!fields.contains(field)) {
                fields.add(field);
            }
        } else if (node instanceof SqlBasicCall) {
            SqlBasicCall call = (SqlBasicCall) node;
            for (SqlNode operand : call.getOperands()) {
                extractFieldsFromExpression(operand, fields);
            }
        } else if (node instanceof SqlSelect) {
            extractFields((SqlSelect) node, new QueryRecord());
        }
    }

    /**
     * 确定查询类型
     */
    private static QueryRecord.QueryType determineQueryType(SqlNode sqlNode) {
        if (!(sqlNode instanceof SqlSelect)) {
            return QueryRecord.QueryType.OTHER;
        }

        SqlSelect select = (SqlSelect) sqlNode;

        // 检查是否有JOIN
        if (containsJoin(select.getFrom())) {
            return QueryRecord.QueryType.JOIN;
        }

        // 检查是否有聚合函数
        if (hasAggregateFunction(select.getSelectList()) || select.getGroup() != null) {
            return QueryRecord.QueryType.AGGREGATE;
        }

        // 检查是否有UNION
        if (select.getUnion() != null) {
            return QueryRecord.QueryType.UNION;
        }

        return QueryRecord.QueryType.SELECT;
    }

    private static boolean containsJoin(SqlNode node) {
        if (node == null) return false;
        if (node instanceof SqlJoin) return true;
        if (node instanceof SqlSelect) {
            SqlSelect select = (SqlSelect) node;
            return containsJoin(select.getFrom());
        }
        return false;
    }

    private static boolean hasAggregateFunction(SqlNodeList selectList) {
        if (selectList == null) return false;
        
        for (SqlNode node : selectList) {
            if (node instanceof SqlBasicCall) {
                SqlBasicCall call = (SqlBasicCall) node;
                String opName = call.getOperator().getName().toUpperCase();
                if ("COUNT".equals(opName) || "SUM".equals(opName) || 
                    "AVG".equals(opName) || "MAX".equals(opName) || 
                    "MIN".equals(opName)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 标准化SQL（去除常量值，用于去重）
     */
    public static String normalizeSql(String sql) {
        if (sql == null) return null;
        
        // 将数字常量替换为占位符
        sql = sql.replaceAll("\\b\\d+\\.\\d+\\b", "#NUM#");
        sql = sql.replaceAll("\\b\\d+\\b", "#NUM#");
        
        // 将字符串常量替换为占位符
        sql = sql.replaceAll("'[^']*'", "'#STR#'");
        
        // 规范化空白
        sql = sql.replaceAll("\\s+", " ").trim();
        
        return sql;
    }

    /**
     * 计算SQL的哈希值
     */
    public static String computeQueryHash(String sql) {
        String normalized = normalizeSql(sql);
        return String.valueOf(normalized.hashCode());
    }

    /**
     * 计算候选反射的哈希值
     */
    public static String computeCandidateHash(String datasetPath, List<String> groupByFields, 
                                              List<String> displayFields, String type) {
        StringBuilder sb = new StringBuilder();
        sb.append(datasetPath).append("|");
        if (groupByFields != null) {
            sb.append(String.join(",", groupByFields)).append("|");
        }
        if (displayFields != null) {
            sb.append(String.join(",", displayFields)).append("|");
        }
        sb.append(type);
        return String.valueOf(sb.toString().hashCode());
    }
}
