package com.retail.model.dao;

import com.retail.model.bean.Sale;
import com.retail.model.bean.SaleItem;
import com.retail.util.DBConnection;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class SalesDAO {

    /**
     * FR4.1 - FR4.4: Record a sale, save items, and deduct stock.
     */
    public boolean recordSale(Sale sale, List<SaleItem> items) {
        Connection conn = null;
        try {
            conn = DBConnection.getConnection();
            conn.setAutoCommit(false); // Start transaction

            // 1. Insert Sales Header
            String saleSql = "INSERT INTO sales (total_amount, payment_method, payment_status, user_id) VALUES (?, ?, ?, ?)";
            PreparedStatement psSale = conn.prepareStatement(saleSql, Statement.RETURN_GENERATED_KEYS);
            psSale.setDouble(1, sale.getTotalAmount());
            psSale.setString(2, sale.getPaymentMethod());
            psSale.setString(3, sale.getPaymentStatus());
            psSale.setInt(4, sale.getUserId());
            psSale.executeUpdate();

            ResultSet rs = psSale.getGeneratedKeys();
            int saleId = 0;
            if (rs.next()) {
                saleId = rs.getInt(1);
                sale.setSaleId(saleId);
            }

            // 2. Insert Sale Items & Update Stock (FR3.2)
            String itemSql = "INSERT INTO sale_items (sale_id, product_id, quantity, unit_price, subtotal) VALUES (?, ?, ?, ?, ?)";
            String stockSql = "UPDATE products SET stock_quantity = stock_quantity - ? WHERE product_id = ? AND stock_quantity >= ?";

            PreparedStatement psItem = conn.prepareStatement(itemSql);
            PreparedStatement psStock = conn.prepareStatement(stockSql);

            for (SaleItem item : items) {
                // Insert Item
                psItem.setInt(1, saleId);
                psItem.setInt(2, item.getProductId());
                psItem.setInt(3, item.getQuantity());
                psItem.setDouble(4, item.getUnitPrice());
                psItem.setDouble(5, item.getSubtotal());
                psItem.addBatch();

                // Deduct Stock
                psStock.setInt(1, item.getQuantity());
                psStock.setInt(2, item.getProductId());
                psStock.setInt(3, item.getQuantity()); // Validation check
                int updatedRows = psStock.executeUpdate();

                if (updatedRows == 0) {
                    throw new SQLException("Insufficient stock for product ID: " + item.getProductId());
                }
            }
            psItem.executeBatch();

            conn.commit();
            return true;
        } catch (SQLException e) {
            if (conn != null) {
                try { conn.rollback(); } catch (SQLException ex) { ex.printStackTrace(); }
            }
            e.printStackTrace();
            return false;
        } finally {
            DBConnection.closeConnection(conn);
        }
    }

    /**
     * FR5.1: Update payment details.
     */
    public boolean updatePaymentStatus(int saleId, String status, String method) {
        String sql = "UPDATE sales SET payment_status = ?, payment_method = ? WHERE sale_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setString(2, method);
            ps.setInt(3, saleId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 获取所有销售记录流水
     */
    public List<Sale> getAllSales() {
        List<Sale> sales = new ArrayList<>();
        String sql = "SELECT s.*, u.full_name FROM sales s JOIN users u ON s.user_id = u.user_id ORDER BY s.sale_date DESC";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Sale s = new Sale();
                s.setSaleId(rs.getInt("sale_id"));
                s.setSaleDate(rs.getTimestamp("sale_date"));
                s.setTotalAmount(rs.getDouble("total_amount"));
                s.setPaymentMethod(rs.getString("payment_method"));
                s.setPaymentStatus(rs.getString("payment_status"));
                s.setSellerName(rs.getString("full_name"));
                s.setCancelReason(rs.getString("cancel_reason")); // 完美对齐 Java Bean
                sales.add(s);
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return sales;
    }

    /**
     * 根据 Sale ID 查询订单主表信息
     */
    public Sale getSaleById(int saleId) {
        String sql = "SELECT * FROM sales WHERE sale_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, saleId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Sale sale = new Sale();
                    sale.setSaleId(rs.getInt("sale_id"));
                    sale.setTotalAmount(rs.getDouble("total_amount"));
                    sale.setPaymentMethod(rs.getString("payment_method"));
                    sale.setPaymentStatus(rs.getString("payment_status"));
                    sale.setUserId(rs.getInt("user_id"));
                    sale.setSaleDate(rs.getTimestamp("sale_date"));
                    sale.setCancelReason(rs.getString("cancel_reason"));
                    return sale;
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * 根据 Sale ID 查询该订单下所有的商品明细列表
     */
    public List<SaleItem> getSaleItemsBySaleId(int saleId) {
        List<SaleItem> items = new ArrayList<>();
        String sql = "SELECT * FROM sale_items WHERE sale_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, saleId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    SaleItem item = new SaleItem();
                    item.setProductId(rs.getInt("product_id"));
                    item.setQuantity(rs.getInt("quantity"));
                    item.setUnitPrice(rs.getDouble("unit_price"));
                    item.setSubtotal(rs.getDouble("subtotal"));
                    items.add(item);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return items;
    }

    /**
     * 核心安全重构：取消销售单、联动回滚库存并保存取消原因
     */
    public boolean cancelSaleById(int saleId, int userId, String reason) {
        String cancelSaleSql = "UPDATE sales SET payment_status = 'CANCELLED', cancel_reason = ?, cancelled_by = ? WHERE sale_id = ?";
        String getItemsSql = "SELECT product_id, quantity FROM sale_items WHERE sale_id = ?";
        String restoreStockSql = "UPDATE products SET stock_quantity = stock_quantity + ? WHERE product_id = ?";
        String logInventorySql = "INSERT INTO inventory_logs (product_id, user_id, change_quantity, reason) VALUES (?, ?, ?, ?)";

        Connection conn = null;
        PreparedStatement psCancel = null;
        PreparedStatement psGetItems = null;
        PreparedStatement psRestore = null;
        PreparedStatement psLog = null;
        ResultSet rs = null;

        try {
            conn = DBConnection.getConnection();
            conn.setAutoCommit(false); // 开启事务控制

            // 1. 更新订单主体状态
            psCancel = conn.prepareStatement(cancelSaleSql);
            psCancel.setString(1, reason);
            psCancel.setInt(2, userId);
            psCancel.setInt(3, saleId);
            psCancel.executeUpdate();

            // 2. 获取商品明细
            psGetItems = conn.prepareStatement(getItemsSql);
            psGetItems.setInt(1, saleId);
            rs = psGetItems.executeQuery();

            psRestore = conn.prepareStatement(restoreStockSql);
            psLog = conn.prepareStatement(logInventorySql);

            // 3. 循环回滚库存并同步写库存变更日志
            while (rs.next()) {
                int productId = rs.getInt("product_id");
                int qty = rs.getInt("quantity");

                // 加回库存
                psRestore.setInt(1, qty);
                psRestore.setInt(2, productId);
                psRestore.addBatch();

                // 写入库存流水账
                psLog.setInt(1, productId);
                psLog.setInt(2, userId);
                psLog.setInt(3, qty); // 正数代表库存加回来
                psLog.setString(4, "Order Cancellation (Order #SAL-" + saleId + "): " + reason);
                psLog.addBatch();
            }

            psRestore.executeBatch();
            psLog.executeBatch();

            conn.commit(); // 提交整个事务
            return true;

        } catch (SQLException e) {
            // 🚨 核心诊断：这样可以在 IntelliJ 底部的 Tomcat 控制台里精准看到 MySQL 到底为什么拒绝你！
            System.err.println("============= ❌ CANCEL ORDER DATABASE CRASH LOG =============");
            System.err.println("Error Message: " + e.getMessage());
            System.err.println("Error Code: " + e.getErrorCode());
            System.err.println("SQL State: " + e.getSQLState());
            e.printStackTrace();
            System.err.println("==============================================================");

            if (conn != null) {
                try { conn.rollback(); } catch (SQLException ex) { ex.printStackTrace(); }
            }
            return false;
        } finally {
            // 💡 修复拼写错误：拼写已修正为两个 l 的 finally
            try {
                if (rs != null) rs.close();
                if (psCancel != null) psCancel.close();
                if (psGetItems != null) psGetItems.close();
                if (psRestore != null) psRestore.close();
                if (psLog != null) psLog.close();
            } catch (SQLException e) { e.printStackTrace(); }
            DBConnection.closeConnection(conn);
        }
    }

    /**
     * 废弃原物理删除方法，改用上面的业务取消 cancelSaleById
     */
    @Deprecated
    public boolean deleteSaleById(int saleId) {
        return false;
    }
}