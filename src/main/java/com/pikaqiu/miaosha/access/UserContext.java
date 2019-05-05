package com.pikaqiu.miaosha.access;

import com.pikaqiu.miaosha.domain.MiaoshaUser;

public class UserContext {

	/**
	 * 线程安全登陆信息
	 */
	private static ThreadLocal<MiaoshaUser> userHolder = new ThreadLocal<MiaoshaUser>();
	
	public static void setUser(MiaoshaUser user) {
		userHolder.set(user);
	}
	
	public static MiaoshaUser getUser() {
		return userHolder.get();
	}

}
