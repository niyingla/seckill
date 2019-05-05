package com.pikaqiu.miaosha.access;

import com.alibaba.fastjson.JSON;
import com.pikaqiu.miaosha.domain.MiaoshaUser;
import com.pikaqiu.miaosha.redis.AccessKey;
import com.pikaqiu.miaosha.redis.RedisService;
import com.pikaqiu.miaosha.result.CodeMsg;
import com.pikaqiu.miaosha.result.Result;
import com.pikaqiu.miaosha.service.MiaoshaUserService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.handler.HandlerInterceptorAdapter;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.OutputStream;

@Service
public class AccessInterceptor  extends HandlerInterceptorAdapter{

	/**
	 * controller调用前 调用拦截器
	 */
	@Autowired
    MiaoshaUserService userService;
	
	@Autowired
	RedisService redisService;

	/**
	 * 前置方法判断是否请求超额 （其他用的默认）
	 * @param request
	 * @param response
	 * @param handler
	 * @return
	 * @throws Exception
	 */
	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
		if(handler instanceof HandlerMethod) {
		    //获取用户
			MiaoshaUser user = getUser(request, response);
			UserContext.setUser(user);
			HandlerMethod hm = (HandlerMethod)handler;
			//获取方法上的访问注解
			AccessLimit accessLimit = hm.getMethodAnnotation(AccessLimit.class);
			if(accessLimit == null) {
				return true;
			}
			int seconds = accessLimit.seconds();
			int maxCount = accessLimit.maxCount();
			boolean needLogin = accessLimit.needLogin();

			//路径为key 可以限制接口访问次数
			String key = request.getRequestURI();
			if(needLogin) {
				//用户未登录 直接返回false
				if(user == null) {
					//返回会话错误
					render(response, CodeMsg.SESSION_ERROR);
					return false;
				}
				key += "_" + user.getId();
			}
			//获取访问数
			AccessKey ak = AccessKey.withExpire(seconds);

			Integer count = redisService.get(ak, key, Integer.class);
			//当前访问数  不存在设置为1
	    	if(count  == null) {
	    		 redisService.set(ak, key, 1);
	    	}else if(count < maxCount) {
	    		//存在 -》自增
	    		 redisService.incr(ak, key);
	    	}else {
	    		//请求超额
	    		render(response, CodeMsg.ACCESS_LIMIT_REACHED);
	    		return false;
	    	}
		}
		return true;
	}

	/**
	 * 输出错误结果
	 * @param response
	 * @param cm
	 * @throws Exception
	 */
	private void render(HttpServletResponse response, CodeMsg cm)throws Exception {
		response.setContentType("application/json;charset=UTF-8");
		OutputStream out = response.getOutputStream();
		String str  = JSON.toJSONString(Result.error(cm));
		out.write(str.getBytes("UTF-8"));
		out.flush();
		out.close();
	}

	/**
	 * 通过登陆token 获取用户信息
	 * @param request
	 * @param response
	 * @return
	 */
	private MiaoshaUser getUser(HttpServletRequest request, HttpServletResponse response) {
		String paramToken = request.getParameter(MiaoshaUserService.COOKI_NAME_TOKEN);
		String cookieToken = getCookieValue(request, MiaoshaUserService.COOKI_NAME_TOKEN);
		if(StringUtils.isEmpty(cookieToken) && StringUtils.isEmpty(paramToken)) {
			return null;
		}
		String token = StringUtils.isEmpty(paramToken)?cookieToken:paramToken;
		return userService.getByToken(response, token);
	}


	/**
	 * 获取需要的cookiName 的cooki值
	 * @param request
	 * @param cookiName
	 * @return
	 */
	private String getCookieValue(HttpServletRequest request, String cookiName) {
		Cookie[]  cookies = request.getCookies();
		if(cookies == null || cookies.length <= 0){
			return null;
		}
		for(Cookie cookie : cookies) {
			if(cookie.getName().equals(cookiName)) {
				return cookie.getValue();
			}
		}
		return null;
	}
	
}
