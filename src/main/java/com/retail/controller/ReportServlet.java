package com.retail.controller;

import com.retail.model.dao.ReportDAO;
import com.retail.model.dao.ProductDAO;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDate;

@WebServlet("/reports")
public class ReportServlet extends HttpServlet {
    private ReportDAO reportDAO;
    private ProductDAO productDAO;

    @Override
    public void init() {
        reportDAO = new ReportDAO();
        productDAO = new ProductDAO();
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        String action = request.getParameter("action");
        if (action == null) action = "salesSummary";

        String start = request.getParameter("startDate");
        String end = request.getParameter("endDate");

        // 💡 核心安全补丁：如果用户刚进页面没选日期，后端Servlet自动赋予最近30天范围，确保初始页面不留白！
        if (start == null || start.trim().isEmpty() || end == null || end.trim().isEmpty()) {
            end = LocalDate.now().toString();
            start = LocalDate.now().minusDays(30).toString(); // 默认查看近30天流水记录
        }

        switch (action) {
            case "lowStock":
                request.setAttribute("lowStockList", productDAO.getLowStockProducts());
                request.getRequestDispatcher("WEB-INF/views/inventory/low-stock.jsp").forward(request, response);
                break;

            case "performance":
                request.setAttribute("performanceData", reportDAO.getProductPerformance());
                request.setAttribute("totalProducts", reportDAO.getTotalProducts());
                request.setAttribute("lowStockCount", reportDAO.getLowStockCount());
                request.getRequestDispatcher("WEB-INF/views/reports/performance.jsp").forward(request, response);
                break;

            case "printReport":
                request.setAttribute("detailedSales", reportDAO.getDetailedSalesRows(start, end));
                request.setAttribute("startDate", start);
                request.setAttribute("endDate", end);
                request.getRequestDispatcher("WEB-INF/views/reports/print-report.jsp").forward(request, response);
                break;

            default:
                // 1. 获取按天求和营业额统计（已排除 CANCELLED 废单）
                request.setAttribute("salesData", reportDAO.getSalesSummary(start, end));

                // 2. 获取该日期范围内的全量单据流水（包含 CANCELLED 废单以及取消原因）
                request.setAttribute("salesList", reportDAO.getFilteredSalesList(start, end));

                // 3. 联动刷新热销榜与低库存
                request.setAttribute("performanceData", reportDAO.getProductPerformance());
                request.setAttribute("lowStockList", productDAO.getLowStockProducts());

                // 将确定有效的日期再带回给网页日期框
                request.setAttribute("selectedStartDate", start);
                request.setAttribute("selectedEndDate", end);

                request.getRequestDispatcher("WEB-INF/views/reports/sales-report.jsp").forward(request, response);
                break;
        }
    }
}