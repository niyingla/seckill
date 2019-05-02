package com.pikaqiu.miaosha.controller;

import java.awt.image.BufferedImage;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.List;

import javax.imageio.ImageIO;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.pikaqiu.miaosha.access.AccessLimit;
import com.pikaqiu.miaosha.domain.MiaoshaOrder;
import com.pikaqiu.miaosha.domain.MiaoshaUser;
import com.pikaqiu.miaosha.rabbitmq.MQSender;
import com.pikaqiu.miaosha.rabbitmq.MiaoshaMessage;
import com.pikaqiu.miaosha.redis.GoodsKey;
import com.pikaqiu.miaosha.redis.MiaoshaKey;
import com.pikaqiu.miaosha.redis.OrderKey;
import com.pikaqiu.miaosha.redis.RedisService;
import com.pikaqiu.miaosha.result.CodeMsg;
import com.pikaqiu.miaosha.result.Result;
import com.pikaqiu.miaosha.service.GoodsService;
import com.pikaqiu.miaosha.service.MiaoshaService;
import com.pikaqiu.miaosha.service.MiaoshaUserService;
import com.pikaqiu.miaosha.service.OrderService;
import com.pikaqiu.miaosha.vo.GoodsVo;

@Controller
@RequestMapping("/miaosha")
public class MiaoshaController implements InitializingBean {

	@Autowired
	MiaoshaUserService userService;
	
	@Autowired
	RedisService redisService;
	
	@Autowired
	GoodsService goodsService;
	
	@Autowired
	OrderService orderService;
	
	@Autowired
	MiaoshaService miaoshaService;
	
	@Autowired
	MQSender sender;

	/**
	 * 本地缓存超额 秒杀数量判断
	 */
	private HashMap<Long, Boolean> localOverMap =  new HashMap<Long, Boolean>();
	
	/**
	 * 系统初始化  加载秒杀商品数量到redis
	 * */
	@Override
	public void afterPropertiesSet() throws Exception {
		//获取所有商品
		List<GoodsVo> goodsList = goodsService.listGoodsVo();
		if(goodsList == null) {
			return;
		}
		//循环库存保存到缓存
		for(GoodsVo goods : goodsList) {
			redisService.set(GoodsKey.getMiaoshaGoodsStock, ""+goods.getId(), goods.getStockCount());
			localOverMap.put(goods.getId(), false);
		}
	}

	/**
	 * 重置所有秒杀参数
	 * @param model
	 * @return
	 */
	@RequestMapping(value="/reset", method=RequestMethod.GET)
    @ResponseBody
    public Result<Boolean> reset(Model model) {
		List<GoodsVo> goodsList = goodsService.listGoodsVo();
		for(GoodsVo goods : goodsList) {
			//重置缓存
			goods.setStockCount(10);
			redisService.set(GoodsKey.getMiaoshaGoodsStock, ""+goods.getId(), 10);
			localOverMap.put(goods.getId(), false);
		}
		//秒杀订单
		redisService.delete(OrderKey.getMiaoshaOrderByUidGid);
		redisService.delete(MiaoshaKey.isGoodsOver);
		miaoshaService.reset(goodsList);
		return Result.success(true);
	}
	
	/**
	 * 本地内存 -》 redis -》 消息队列 -》 慢慢消费（读/写mysql）
	 * 所有操作尽量不再数据库 数据库最好放在消息队列后 慢慢操作
	 * QPS:1306
	 * 5000 * 10
	 * QPS: 2114
	 * */
    @RequestMapping(value="/{path}/do_miaosha", method=RequestMethod.POST)
    @ResponseBody
    public Result<Integer> miaosha(Model model,MiaoshaUser user,
    		@RequestParam("goodsId")long goodsId,
    		@PathVariable("path") String path) {
    	model.addAttribute("user", user);
    	if(user == null) {
    		return Result.error(CodeMsg.SESSION_ERROR);
    	}
    	//验证path
    	boolean check = miaoshaService.checkPath(user, goodsId, path);
    	if(!check){
    		return Result.error(CodeMsg.REQUEST_ILLEGAL);
    	}
    	//内存标记，减少redis访问
    	boolean over = localOverMap.get(goodsId);
    	if(over) {
    		return Result.error(CodeMsg.MIAO_SHA_OVER);
    	}
    	//预减库存
    	long stock = redisService.decr(GoodsKey.getMiaoshaGoodsStock, ""+goodsId);
    	if(stock < 0) {
    		//重置内存标记
    		 localOverMap.put(goodsId, true);
    		return Result.error(CodeMsg.MIAO_SHA_OVER);
    	}
    	//判断是否已经秒杀到了
    	MiaoshaOrder order = orderService.getMiaoshaOrderByUserIdGoodsId(user.getId(), goodsId);
    	if(order != null) {
    		return Result.error(CodeMsg.REPEATE_MIAOSHA);
    	}
    	//入队
    	MiaoshaMessage mm = new MiaoshaMessage();
    	mm.setUser(user);
    	mm.setGoodsId(goodsId);
    	sender.sendMiaoshaMessage(mm);
    	return Result.success(0);//排队中
    	/*
    	//判断库存
    	GoodsVo goods = goodsService.getGoodsVoByGoodsId(goodsId);//10个商品，req1 req2
    	int stock = goods.getStockCount();
    	if(stock <= 0) {
    		return Result.error(CodeMsg.MIAO_SHA_OVER);
    	}
    	//判断是否已经秒杀到了
    	MiaoshaOrder order = orderService.getMiaoshaOrderByUserIdGoodsId(user.getId(), goodsId);
    	if(order != null) {
    		return Result.error(CodeMsg.REPEATE_MIAOSHA);
    	}
    	//减库存 下订单 写入秒杀订单
    	OrderInfo orderInfo = miaoshaService.miaosha(user, goods);
        return Result.success(orderInfo);
        */
    }
    
    /**
	 * 获取秒杀结果
     * orderId：成功
     * -1：秒杀失败
     * 0： 排队中
     * */
    @RequestMapping(value="/result", method=RequestMethod.GET)
    @ResponseBody
    public Result<Long> miaoshaResult(Model model,MiaoshaUser user,
    		@RequestParam("goodsId")long goodsId) {
    	model.addAttribute("user", user);
    	if(user == null) {
    		return Result.error(CodeMsg.SESSION_ERROR);
    	}
    	long result = miaoshaService.getMiaoshaResult(user.getId(), goodsId);
    	return Result.success(result);
    }

	/**
	 * 限流秒杀
	 * @param request
	 * @param user
	 * @param goodsId
	 * @param verifyCode
	 * @return
	 */
    @AccessLimit(seconds=5, maxCount=5, needLogin=true)
    @RequestMapping(value="/path", method=RequestMethod.GET)
    @ResponseBody
    public Result<String> getMiaoshaPath(HttpServletRequest request, MiaoshaUser user,
    		@RequestParam("goodsId")long goodsId,
    		@RequestParam(value="verifyCode", defaultValue="0")int verifyCode
    		) {
    	if(user == null) {
    		return Result.error(CodeMsg.SESSION_ERROR);
    	}
    	boolean check = miaoshaService.checkVerifyCode(user, goodsId, verifyCode);
    	if(!check) {
    		return Result.error(CodeMsg.REQUEST_ILLEGAL);
    	}
    	String path  =miaoshaService.createMiaoshaPath(user, goodsId);
    	return Result.success(path);
    }
    
    
    @RequestMapping(value="/verifyCode", method=RequestMethod.GET)
    @ResponseBody
    public Result<String> getMiaoshaVerifyCod(HttpServletResponse response,MiaoshaUser user,
    		@RequestParam("goodsId")long goodsId) {
    	if(user == null) {
    		return Result.error(CodeMsg.SESSION_ERROR);
    	}
    	try {
    		BufferedImage image  = miaoshaService.createVerifyCode(user, goodsId);
    		OutputStream out = response.getOutputStream();
    		ImageIO.write(image, "JPEG", out);
    		out.flush();
    		out.close();
    		return null;
    	}catch(Exception e) {
    		e.printStackTrace();
    		return Result.error(CodeMsg.MIAOSHA_FAIL);
    	}
    }
}
