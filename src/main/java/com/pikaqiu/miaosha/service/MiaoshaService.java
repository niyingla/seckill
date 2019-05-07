package com.pikaqiu.miaosha.service;

import com.pikaqiu.miaosha.domain.MiaoshaOrder;
import com.pikaqiu.miaosha.domain.MiaoshaUser;
import com.pikaqiu.miaosha.domain.OrderInfo;
import com.pikaqiu.miaosha.redis.MiaoshaKey;
import com.pikaqiu.miaosha.redis.RedisService;
import com.pikaqiu.miaosha.util.MD5Util;
import com.pikaqiu.miaosha.util.UUIDUtil;
import com.pikaqiu.miaosha.vo.GoodsVo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Random;

@SuppressWarnings("restriction")
@Service
public class MiaoshaService {
	
	@Autowired
	GoodsService goodsService;
	
	@Autowired
	OrderService orderService;
	
	@Autowired
	RedisService redisService;

	@Transactional
	public OrderInfo miaosha(MiaoshaUser user, GoodsVo goods) {
		//减库存 下订单 写入秒杀订单
		boolean success = goodsService.reduceStock(goods);
		if(success) {
			//order_info maiosha_order
			return orderService.createOrder(user, goods);
		}else {
			setGoodsOver(goods.getId());
			return null;
		}
	}

	/**
	 * 获取秒杀结果
	 * @param userId
	 * @param goodsId
	 * @return
	 */
	public long getMiaoshaResult(Long userId, long goodsId) {
		MiaoshaOrder order = orderService.getMiaoshaOrderByUserIdGoodsId(userId, goodsId);
		//秒杀成功
		if(order != null) {
			return order.getOrderId();
		}else {
			//获取是否超限
			boolean isOver = getGoodsOver(goodsId);
			if(isOver) {
				return -1;
			}else {
				return 0;
			}
		}
	}

	/**
	 * 设置redis 库存超了
	 * @param goodsId
	 */
	private void setGoodsOver(Long goodsId) {
		redisService.set(MiaoshaKey.isGoodsOver, ""+goodsId, true);
	}

	/**
	 * 获取redis库存是否超
	 * @param goodsId
	 * @return
	 */
	private boolean getGoodsOver(long goodsId) {
		return redisService.exists(MiaoshaKey.isGoodsOver, ""+goodsId);
	}

	/**
	 * 循环重置库存  删除订单
	 * @param goodsList
	 */
	public void reset(List<GoodsVo> goodsList) {
		goodsService.resetStock(goodsList);
		orderService.deleteOrders();
	}

	/**
	 * 检查路径
	 * @param user
	 * @param goodsId
	 * @param path
	 * @return
	 */
	public boolean checkPath(MiaoshaUser user, long goodsId, String path) {
		if(user == null || path == null) {
			return false;
		}
		String pathOld = redisService.get(MiaoshaKey.getMiaoshaPath, ""+user.getId() + "_"+ goodsId, String.class);
		return path.equals(pathOld);
	}

	/**
	 * 创建mdk加密路径
	 * @param user
	 * @param goodsId
	 * @return
	 */
	public String createMiaoshaPath(MiaoshaUser user, long goodsId) {
		if(user == null || goodsId <=0) {
			return null;
		}

		String str = MD5Util.md5(UUIDUtil.uuid()+"123456");
    	redisService.set(MiaoshaKey.getMiaoshaPath, ""+user.getId() + "_"+ goodsId, str);
		return str;
	}

	/**
	 * 创建图形验证码
	 * @param user
	 * @param goodsId
	 * @return
	 */
	public BufferedImage createVerifyCode(MiaoshaUser user, long goodsId) {
		if(user == null || goodsId <=0) {
			return null;
		}
		int width = 80;
		int height = 32;
		//create the image
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics g = image.getGraphics();
		// set the background color
		g.setColor(new Color(0xDCDCDC));
		g.fillRect(0, 0, width, height);
		// draw the border
		g.setColor(Color.black);
		g.drawRect(0, 0, width - 1, height - 1);
		// create a random instance to generate the codes
		Random rdm = new Random();
		// make some confusion
		for (int i = 0; i < 50; i++) {
			int x = rdm.nextInt(width);
			int y = rdm.nextInt(height);
			g.drawOval(x, y, 0, 0);
		}
		// generate a random code
		String verifyCode = generateVerifyCode(rdm);
		g.setColor(new Color(0, 100, 0));
		g.setFont(new Font("Candara", Font.BOLD, 24));
		g.drawString(verifyCode, 8, 24);
		g.dispose();
		//把验证码存到redis中
		int rnd = calc(verifyCode);
		redisService.set(MiaoshaKey.getMiaoshaVerifyCode, user.getId()+","+goodsId, rnd);
		//输出图片	
		return image;
	}

	/**
	 * 校验数字验证码
	 * @param user
	 * @param goodsId
	 * @param verifyCode
	 * @return
	 */
	public boolean checkVerifyCode(MiaoshaUser user, long goodsId, int verifyCode) {
		//不存在或者错误 直接返回
		if(user == null || goodsId <=0) {
			return false;
		}
		//获取之前的结果
		Integer codeOld = redisService.get(MiaoshaKey.getMiaoshaVerifyCode, user.getId()+","+goodsId, Integer.class);
		//判空
		if(codeOld == null || codeOld - verifyCode != 0 ) {
			return false;
		}
		//结果争取 直接删除
		redisService.delete(MiaoshaKey.getMiaoshaVerifyCode, user.getId()+","+goodsId);
		return true;
	}

	/**
	 * 获取验证表达式的值
	 * @param exp
	 * @return
	 */
	private static int calc(String exp) {
		try {
			//创建js引擎管理
			ScriptEngineManager manager = new ScriptEngineManager();
			//获取js引擎
			ScriptEngine engine = manager.getEngineByName("JavaScript");
			//获取表达式的值
			return (Integer)engine.eval(exp);
		}catch(Exception e) {
			e.printStackTrace();
			return 0;
		}
	}

	/**
	 * 定义运算符
	 */
	private static char[] ops = new char[] {'+', '-', '*'};

	/**
	 * 获取随机计算字符串
	 * + - *
	 * */
	private String generateVerifyCode(Random rdm) {
		//前三位计算数字  获取
		int num1 = rdm.nextInt(10);
	    int num2 = rdm.nextInt(10);
		int num3 = rdm.nextInt(10);
		//计算符号获取
		char op1 = ops[rdm.nextInt(3)];
		char op2 = ops[rdm.nextInt(3)];
		//生成算式
		String exp = ""+ num1 + op1 + num2 + op2 + num3;
		return exp;
	}
}
