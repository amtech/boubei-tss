package com.boubei.cloud;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.boubei.tss.framework.Global;
import com.boubei.tss.framework.web.display.SuccessMessageEncoder;
import com.boubei.tss.framework.web.display.XmlPrintWriter;
import com.boubei.tss.um.entity.User;

@WebServlet(urlPatterns="/reg.in")
public class Register extends HttpServlet {
	
	private static final long serialVersionUID = -740569423483772472L;
    
    private IRegiterService service;
 
	public void init() {
		service = (IRegiterService) Global.getBean("RegiterService");
	}
	
	public void destroy() {
		service = null;
	}
	
    protected void doGet(HttpServletRequest request,
            HttpServletResponse response) throws ServletException, IOException {
        doPost(request, response);
    }
 
    protected void doPost(HttpServletRequest request, HttpServletResponse response) 
    		throws ServletException, IOException {
    	
        User user = new User();
        
        // 前台传递的参数
        user.setLoginName(request.getParameter("loginName"));
        user.setPassword(request.getParameter("password"));
        user.setUserName(request.getParameter("userName"));
        user.setEmail(request.getParameter("email"));
        user.setTelephone(request.getParameter("telephone"));
        
        user.setPasswordQuestion("my phone?");
        user.setPasswordAnswer( user.getTelephone() );
        
        String domain = request.getParameter("domain");
        
        service.register(user, domain);

        response.setContentType("text/html;charset=UTF-8");
        SuccessMessageEncoder encoder = new SuccessMessageEncoder("用户注册成功！", "用户注册成功！");
        encoder.print(new XmlPrintWriter(response.getWriter()));
    }
}
