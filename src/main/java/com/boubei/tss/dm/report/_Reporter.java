/* ==================================================================   
 * Created [2016-3-5] by Jon.King 
 * ==================================================================  
 * TSS 
 * ================================================================== 
 * mailTo:boubei@163.com
 * Copyright (c) boubei.com, 2015-2018 
 * ================================================================== 
 */

package com.boubei.tss.dm.report;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeUtility;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import com.boubei.tss.EX;
import com.boubei.tss.dm.DMUtil;
import com.boubei.tss.dm.DataExport;
import com.boubei.tss.dm.dml.SQLExcutor;
import com.boubei.tss.dm.report.log.AccessLogRecorder;
import com.boubei.tss.framework.Global;
import com.boubei.tss.framework.exception.BusinessException;
import com.boubei.tss.framework.persistence.pagequery.PageInfo;
import com.boubei.tss.framework.sso.Environment;
import com.boubei.tss.framework.web.display.grid.DefaultGridNode;
import com.boubei.tss.framework.web.display.grid.GridDataEncoder;
import com.boubei.tss.framework.web.display.grid.IGridNode;
import com.boubei.tss.framework.web.filter.Filter8APITokenCheck;
import com.boubei.tss.framework.web.mvc.BaseActionSupport;
import com.boubei.tss.modules.log.IBusinessLogger;
import com.boubei.tss.modules.log.Log;
import com.boubei.tss.util.EasyUtils;
import com.boubei.tss.util.MailUtil;
import com.boubei.tss.util.URLUtil;

@Controller
@RequestMapping( {"/data", "/api", "/display"} )
public class _Reporter extends BaseActionSupport {
    
    @Autowired ReportService reportService;
    
	@RequestMapping("/{nameOrID}/define")
    @ResponseBody
    public Object getReportDefine(HttpServletRequest request, @PathVariable("nameOrID") String nameOrID) {
		// 如果是【报表名：rpName】参数传过来，优先通过报表名查询
		String queryString = request.getQueryString();
		String rpName =  URLUtil.parseQueryString(queryString).get("rpName");
		// if(rpName != null ) log.info( rpName  + " vs " + request.getParameter("rpName") );  // GET Request里，getParameter取出的是ISO-ISO-8859-1编码
		
		rpName = (String) EasyUtils.checkNull(rpName, request.getParameter("rpName"));
		nameOrID = (String) EasyUtils.checkNull(rpName, nameOrID);
		
		Long reportId = null;
    	try { // 先假定是报表ID（Long型）
    		reportId = reportService.getReportId("id", Long.valueOf(nameOrID), Report.TYPE1);
    	} 
    	catch(Exception e) { 
    		reportId = reportService.getReportId("name", nameOrID, Report.TYPE1);
    	}
    	if(reportId == null) {
    		throw new BusinessException(EX.parse(EX.DM_14, nameOrID));
    	}
    	
		Report report = reportService.getReport(reportId);
		
		String name = report.getName();
		String param = report.getParam();
		String displayUri = report.getDisplayUri();
		boolean hasScript = !EasyUtils.isNullOrEmpty(report.getScript());
		Integer mailable  = report.getMailable();
		String remark = EasyUtils.obj2String( report.getRemark() );
		String queryUri = report.getParamUri();
		
		return new Object[] {name, param, displayUri, hasScript, mailable, remark, queryUri, reportId};
    }
	
    /**
     * 1、完成接口调用时令牌校验 & 自动登录
     * 2、根据每个报表的具体配置来确定使用具体的缓存策略。可分为：不缓存、按用户缓存、按参数缓存、按域缓存。
     * 注：加入企业域后，SQL里带上了${filterByDomain}，需要再加一种，按域缓存
     */
    private Object checkLoginAndCache(HttpServletRequest request, Long reportId) {
    	
    	Report report = reportService.getReport(reportId, false);
    	String script = (report.getScript()+"").toLowerCase();
    	
    	/* 其它系统调用接口时，传入其在TSS注册的用户ID; 检查令牌，令牌有效则自动完成登陆 */
    	Filter8APITokenCheck.checkAPIToken(request, report);
    	
    	/* 如果传入的参数要求不取缓存的数据，则返回当前时间戳作为userID，以触发缓存更新。*/
    	Object cacheFlag;
    	if( "true".equals(request.getParameter("noCache")) ) {
    		cacheFlag = System.currentTimeMillis(); // 按时间戳缓存，白存了，永远无法再次命中
    	}
    	else if( "true".equals(request.getParameter("uCache")) 
    			|| Pattern.compile("from[\\s]*\\$\\{").matcher(script).find() ) { // 面向数据表查询
    		cacheFlag = Environment.getUserId();  // 按【用户 + 参数】缓存
    	}
    	else {
    		cacheFlag = EasyUtils.checkNull(Environment.getDomain(), -1L); // 按查询【域 + 参数】缓存
    	}
    	return cacheFlag;
    }
 
    @RequestMapping("/{reportId}/{page}/{pagesize}")
    public void showAsGrid(HttpServletRequest request, HttpServletResponse response, 
            @PathVariable("reportId") Long reportId, 
            @PathVariable("page") int page,
            @PathVariable("pagesize") int pagesize) {
    	
    	long start = System.currentTimeMillis();
    	Map<String, String> requestMap = DMUtil.getRequestMap(request, false);
		Object cacheFlag = checkLoginAndCache(request, reportId);
		SQLExcutor excutor = reportService.queryReport(reportId, requestMap, page, pagesize, cacheFlag);
    	
		AccessLogRecorder.outputAccessLog(reportService, reportId, "showAsGrid", requestMap, start);
        
        List<IGridNode> list = new ArrayList<IGridNode>();
        for(Map<String, Object> item : excutor.result) {
            DefaultGridNode gridNode = new DefaultGridNode();
            gridNode.getAttrs().putAll(item);
            list.add(gridNode);
        }
        GridDataEncoder gEncoder = new GridDataEncoder(list, excutor.getGridTemplate());
        
        PageInfo pageInfo = new PageInfo();
        pageInfo.setPageSize(pagesize);
        pageInfo.setTotalRows(excutor.count);
        pageInfo.setPageNum(page);
        
        print(new String[] {"ReportData", "PageInfo"}, new Object[] {gEncoder, pageInfo});
    }
    
    @RequestMapping("/export/{reportId}/{page}/{pagesize}")
    public void exportAsCSV(HttpServletRequest request, HttpServletResponse response, 
            @PathVariable("reportId") Long reportId, 
            @PathVariable("page") int page,
            @PathVariable("pagesize") int pagesize) {
        
    	long start = System.currentTimeMillis();
    	Map<String, String> requestMap = DMUtil.getRequestMap(request, true);
		Object cacheFlag = checkLoginAndCache(request, reportId);
		String email = requestMap.remove("email");
		
		SQLExcutor excutor = reportService.queryReport(reportId, requestMap, page, pagesize, cacheFlag);
		
		String fileName = reportId + "-" + start + ".csv";
        String exportPath;
        
        // 如果导出数据超过了pageSize（前台为导出设置的pageSize为10万），则不予导出并给与提示
		if(pagesize > 0 && excutor.count > pagesize) {
			List<Object[]> result = new ArrayList<Object[]>();
			result.add(new Object[] {"您当前查询导出的数据有" +excutor.count+ "行, 超过了系统单次导出上限【" +pagesize+ "行】，请缩短查询范围，分批导出。"});
			
			exportPath = DataExport.getExportPath() + "/" + fileName;
			DataExport.exportCSV(exportPath, result, Arrays.asList("result"));
		}
		else {
			// 先输出查询结果到服务端的导出文件中
			exportPath = DataExport.exportCSV(fileName, excutor.result, excutor.selectFields);
		}
		
		if( email != null ) {
			String _ms = (String) EasyUtils.checkNull( requestMap.get("_ms"), MailUtil.DEFAULT_MS );
			JavaMailSenderImpl sender = MailUtil.getMailSender(_ms);
			MimeMessage mailMessage = sender.createMimeMessage();
			
			try {
				// 设置utf-8或GBK编码，否则邮件会有乱码
				MimeMessageHelper messageHelper = new MimeMessageHelper(mailMessage, true, "utf-8");
				messageHelper.setFrom( MailUtil.getEmailFrom(_ms) ); // 发送者
				messageHelper.setTo( email );                         // 接受者
				messageHelper.setSubject(EX.TIMER_REPORT + ":" + reportService.getReport(reportId, false).getName());  // 主题
				
				messageHelper.addAttachment(MimeUtility.encodeWord(fileName), new File(exportPath));
				messageHelper.setText("详细请查收附件", true);
				sender.send(mailMessage);
			}
			catch(Exception e) {
				log.error(" error when send report email ", e);
			}
		}
		else { // 下载上一步生成的附件
	        DataExport.downloadFileByHttp(response, exportPath);
		}
        
        AccessLogRecorder.outputAccessLog(reportService, reportId, "exportAsCSV", requestMap, start);
    }
    
    /**
     * 将前台（一般为生成好的table数据）数据导出成CSV格式
     */
    @RequestMapping("/export/data2csv")
    @ResponseBody
    public String[] data2CSV(HttpServletRequest request, HttpServletResponse response) {
    	Map<String, String> requestMap = DMUtil.getRequestMap(request, false);
    	String name = requestMap.get("name");
    	String data = requestMap.get("data");
		
		String fileName = name + "-" + System.currentTimeMillis() + ".csv";
        String exportPath = DataExport.getExportPath() + "/" + fileName;
 
		// 先输出内容到服务端的导出文件中
        DataExport.exportCSV(exportPath, data);
        
        // 记录导出日志
 		Log excuteLog = new Log(name, Environment.getUserCode() + "导出了网页数据：" + fileName );
     	excuteLog.setOperateTable("网页数据导出");
         ((IBusinessLogger) Global.getBean("BusinessLogger")).output(excuteLog);
        
        return new String[] { fileName };
    }
    
    @RequestMapping("/download/{fileName}")
    public void download(HttpServletResponse response, @PathVariable("fileName") String fileName) {
        String basePath = DataExport.getExportPath();
        String exportPath = basePath + "/" + fileName + ".csv";
        DataExport.downloadFileByHttp(response, exportPath);
    }
    
    /**
     * report可能是report的ID 也 可能是 Name.
     * 注：一次最多能取10万行。
     */
    @RequestMapping("/json/{report}")
    @ResponseBody
    public Object showAsJson(HttpServletRequest request, HttpServletResponse response, 
    		@PathVariable("report") String report) {
    	
    	/* 允许跨域访问。 经测试JQuery.ajax请求可以跨域调用成功，tssJS.ajax不行 */
    	response.addHeader("Access-Control-Allow-Origin", "*"); 
    	
    	Long reportId = null;
    	try { // 先假定是报表ID（Long型）
    		reportId = reportService.getReportId("id", Long.valueOf(report), Report.TYPE1);
    	} 
    	catch(Exception e) { }
    	
    	// 按名字再查一遍
    	if(reportId == null) {
    		report = report.replaceFirst("rpn-", ""); // 如果报表的名称为数字，则写法如：rpn-122
    		reportId = reportService.getReportId("name", report, Report.TYPE1);
    	}
    	if(reportId == null) {
    		throw new BusinessException( EX.parse(EX.DM_18, report) );
    	}
    	
    	String jsonpCallback = request.getParameter("jsonpCallback"); // jsonp是用GET请求
    	Map<String, String> requestMap = DMUtil.getRequestMap(request, jsonpCallback != null);
    	
    	Object page = requestMap.get("page");
    	Object pagesize = requestMap.get("pagesize");
    	pagesize = EasyUtils.checkNull(pagesize, requestMap.get("rows"), 10*10000); // easyUI用rows
    	
    	int _pagesize = EasyUtils.obj2Int(pagesize);
    	int _page = page != null ? EasyUtils.obj2Int(page) : 1;
    			
    	long start = System.currentTimeMillis();
        Object cacheFlag = checkLoginAndCache(request, reportId);
		SQLExcutor excutor = reportService.queryReport(reportId, requestMap, _page, _pagesize, cacheFlag);
        
        // 对一些转换为json为报错的类型值进行预处理
        for(Map<String, Object> row : excutor.result ) {
        	for(String key : row.keySet()) {
        		Object value = row.get(key);
        		row.put(key, DMUtil.preTreatValue(value));
        	}
        }
        
        AccessLogRecorder.outputAccessLog(reportService, reportId, "showAsJson", requestMap, start);
        
        if(page != null) {
        	Map<String, Object> returlVal = new HashMap<String, Object>();
        	returlVal.put("total", excutor.count);
        	returlVal.put("rows", excutor.result);
        	return returlVal;
        }
        
        return excutor.result;
    }
 
    @RequestMapping("/jsonp/{report}")
    public void showAsJsonp(HttpServletRequest request, HttpServletResponse response, 
    		@PathVariable("report") String report) {
    	
        // 如果定义了jsonpCallback参数，则为jsonp调用。示例参考：boubei-ui/JSONP.html
        String jsonpCallback = request.getParameter("jsonpCallback");
        jsonpCallback = (String) EasyUtils.checkNull(jsonpCallback, "console.log");
        
		String json = EasyUtils.obj2Json( showAsJson(request, response, report) );
    	print(jsonpCallback + "(" + json + ")");
    }
}
