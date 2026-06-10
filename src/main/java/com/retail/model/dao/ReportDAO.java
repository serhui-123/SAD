package com.retail.model.dao;

import com.retail.util.DBConnection;
import java.sql.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ReportDAO {

    /**
     * 1. 获取销售日志流水 - 放行 CANCELLED 并包含原因（用于大看板最下方列表完美展示状态与原因）
     */
    public List<Map<String, Object>> getFilteredSalesList(String startDate, String endDate) {
        List<Map<String, Object>> list = new ArrayList<>();

        // 💡 严谨防空：如果用户没选日期，默认查今天
        if (startDate == null || startDate.trim().isEmpty() || endDate == null || endDate.trim().isEmpty()) {
            startDate = LocalDate.now().toString();
            endDate = LocalDate.now().toString();
        }

        String sql = "SELECT s.sale_id, u.full_name as seller_name, s.total_amount, " +
                "s.payment_method, s.payment_status, s.sale_date, s.cancel_reason " +
                "FROM sales s " +
                "JOIN users u ON s.user_id = u.user_id " +
                "WHERE DATE(s.sale_date) >= ? AND DATE(s.sale_date) <= ? " +
                "ORDER BY s.sale_date DESC";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, startDate);
            ps.setString(2, endDate);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new HashMap<>();
                    row.put("saleId", rs.getInt("sale_id"));
                    row.put("sellerName", rs.getString("seller_name"));
                    row.put("totalAmount", rs.getDouble("total_amount"));
                    row.put("paymentMethod", rs.getString("payment_method"));
                    row.put("paymentStatus", rs.getString("payment_status"));
                    row.put("saleDate", rs.getTimestamp("sale_date"));
                    row.put("cancelReason", rs.getString("cancel_reason"));
                    list.add(row);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }

    /**
     * 2. 按日期统计每日营业额总和 - 💡 核心修复：这里必须完美剔除 CANCELLED 订单，同时精准对齐日期格式！
     */
    public Map<String, Double> getSalesSummary(String startDate, String endDate) {
        Map<String, Double> reportData = new LinkedHashMap<>();

        // 💡 确保默认日期范围跟上方流水完全一致，防止查空
        if (startDate == null || startDate.trim().isEmpty() || endDate == null || endDate.trim().isEmpty()) {
            startDate = LocalDate.now().toString();
            endDate = LocalDate.now().toString();
        }

        // 强行使用 DATE(sale_date) 格式化汇总，保证即便包含时分秒也能精准被 >= 和 <= 拦截
        String sql = "SELECT DATE(sale_date) as date, SUM(total_amount) as daily_total " +
                "FROM sales WHERE DATE(sale_date) >= ? AND DATE(sale_date) <= ? " +
                "AND payment_status != 'CANCELLED' " +
                "GROUP BY DATE(sale_date) ORDER BY DATE(sale_date) ASC";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, startDate);
            ps.setString(2, endDate);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    reportData.put(rs.getString("date"), rs.getDouble("daily_total"));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return reportData;
    }

    /**
     * 3. Top 10 畅销排行 - 自动剔除已被取消订单中的商品件数
     */
    public Map<String, Integer> getProductPerformance() {
        Map<String, Integer> performance = new LinkedHashMap<>();
        String sql = "SELECT p.product_name, SUM(si.quantity) as total_sold " +
                "FROM sale_items si " +
                "JOIN sales s ON si.sale_id = s.sale_id " +
                "JOIN products p ON si.product_id = p.product_id " +
                "WHERE s.payment_status != 'CANCELLED' " +
                "GROUP BY p.product_id ORDER BY total_sold DESC LIMIT 10";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                performance.put(rs.getString("product_name"), rs.getInt("total_sold"));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return performance;
    }

    /**
     * 4. 获取今日总营业额（主页卡片用） - 自动剔除已取消的订单
     */
    public double getTodaySales() {
        String sql = "SELECT SUM(total_amount) FROM sales WHERE DATE(sale_date) = CURDATE() AND payment_status != 'CANCELLED'";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) return rs.getDouble(1);
        } catch (SQLException e) { e.printStackTrace(); }
        return 0.0;
    }

    /**
     * 5. 获取商品总种类数
     */
    public int getTotalProducts() {
        String sql = "SELECT COUNT(*) FROM products";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) { e.printStackTrace(); }
        return 0;
    }

    /**
     * 6. 获取低库存警报商品数量
     */
    public int getLowStockCount() {
        String sql = "SELECT COUNT(*) FROM products WHERE stock_quantity <= low_stock_threshold";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) { e.printStackTrace(); }
        return 0;
    }

    /**
     * 7. 获取今日有效交易次数 - 自动剔除已取消的订单
     */
    public int getTodayTransactions() {
        String sql = "SELECT COUNT(*) FROM sales WHERE DATE(sale_date) = CURDATE() AND payment_status != 'CANCELLED'";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) { e.printStackTrace(); }
        return 0;
    }

    /**
     * 8. 保留原始明细流水（用于独立纸张打印查账）
     */
    public List<Map<String, Object>> getDetailedSalesRows(String startDate, String endDate) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (startDate == null || startDate.trim().isEmpty() || endDate == null || endDate.trim().isEmpty()) {
            endDate = LocalDate.now().toString();
            startDate = LocalDate.now().toString();
        }
        String sql = "SELECT s.sale_id, p.product_name, si.quantity, si.subtotal, " +
                "s.payment_method, s.payment_status, s.sale_date, s.cancel_reason " +
                "FROM sale_items si " +
                "JOIN sales s ON si.sale_id = s.sale_id " +
                "JOIN products p ON si.product_id = p.product_id " +
                "WHERE DATE(s.sale_date) >= ? AND DATE(s.sale_date) <= ? " +
                "ORDER BY s.sale_id DESC, p.product_name ASC";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, startDate);
            ps.setString(2, endDate);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new HashMap<>();
                    row.put("saleId", rs.getInt("sale_id"));
                    row.put("productName", rs.getString("product_name"));
                    row.put("quantity", rs.getInt("quantity"));
                    row.put("subtotal", rs.getDouble("subtotal"));
                    row.put("paymentMethod", rs.getString("payment_method"));
                    row.put("paymentStatus", rs.getString("payment_status"));
                    row.put("saleDate", rs.getTimestamp("sale_date"));
                    row.put("cancelReason", rs.getString("cancel_reason"));
                    list.add(row);
                }
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return list;
    }
}