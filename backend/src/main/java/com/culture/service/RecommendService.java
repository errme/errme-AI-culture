package com.culture.service;



import com.culture.entity.Culture;
import org.apache.mahout.cf.taste.common.TasteException;

import java.util.List;

/**
 *
 * @descriptiton:推荐接口
 *
 */
public interface RecommendService {

 // 基于用户推荐方法
 List<Culture> getRecommendItemsByUser (Long userId , int howMany );

 // 基于内容的商品推荐
 // List<Culture> getRecommendItemsByItem (Long userId , Long itemId , int howMany);
}