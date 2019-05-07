package com.pikaqiu.miaosha.util;

import org.apache.commons.codec.digest.DigestUtils;

public class MD5Util {
	
	public static String md5(String src) {
		return DigestUtils.md5Hex(src);
	}
	
	private static final String salt = "1a2b3c4d";

	/**
	 * 前端有此段代码  可以防止网络劫持
	 * 用户输入密码  - 》 表单提交后台密码
	 * @param inputPass
	 * @return
	 */
	public static String inputPassToFormPass(String inputPass) {
		String str = ""+salt.charAt(0)+salt.charAt(2) + inputPass +salt.charAt(5) + salt.charAt(4);
		return md5(str);
	}

	/**
	 * 后台由此段代码 可以让拥有数据库或者拥有debug权限的人也无法知道密码
	 * 表单提交后台密码 -》 数据库保存密码
	 * @param formPass
	 * @param salt 前端盐
	 * @return
	 */
	public static String formPassToDBPass(String formPass, String salt) {
		String str = ""+salt.charAt(0)+salt.charAt(2) + formPass +salt.charAt(5) + salt.charAt(4);
		return md5(str);
	}
	/**
	 *
	 * 1 前端&固定盐 md5 2 后台&数据库盐md5
	 * 
	 * 用户输入密码  - 》 数据库保存密码
	 * @param inputPass
	 * @param saltDB  数据库盐
	 * @return
	 */
	
	public static String inputPassToDbPass(String inputPass, String saltDB) {
		String formPass = inputPassToFormPass(inputPass);
		String dbPass = formPassToDBPass(formPass, saltDB);
		return dbPass;
	}
	
	public static void main(String[] args) {
		System.out.println(inputPassToFormPass("123456"));//d3b1294a61a07da9b49b6e22b2cbd7f9
//		System.out.println(formPassToDBPass(inputPassToFormPass("123456"), "1a2b3c4d"));
//		System.out.println(inputPassToDbPass("123456", "1a2b3c4d"));//b7797cce01b4b131b433b6acf4add449
	}
	
}
