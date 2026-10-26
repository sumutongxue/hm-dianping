package com.hmdp.service.impl;
import javax.annotation.Resource;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.ReactiveRedisOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */

@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource

    private StringRedisTemplate stringRedisTemplate;


    @Override
    public Result queryById(Long id) {
        //1.redis查询缓存
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson  = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        //不存在，根据id查询数据库
        Shop shop = getById(id);
        if(shop==null){
            return Result.fail("店铺不存在");
        }else{
            //将查询结果写入redis
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop));
            return Result.ok(shop);
        }



    }


}
