package com.culture.service.impl;


import com.culture.entity.Culture;
import com.culture.mapper.CultureMapper;
import com.culture.service.RecommendService;
import org.apache.mahout.cf.taste.common.TasteException;
import org.apache.mahout.cf.taste.impl.neighborhood.NearestNUserNeighborhood;
import org.apache.mahout.cf.taste.impl.recommender.GenericUserBasedRecommender;
import org.apache.mahout.cf.taste.impl.similarity.CityBlockSimilarity;
import org.apache.mahout.cf.taste.model.DataModel;
import org.apache.mahout.cf.taste.neighborhood.UserNeighborhood;
import org.apache.mahout.cf.taste.recommender.RecommendedItem;
import org.apache.mahout.cf.taste.recommender.Recommender;
import org.apache.mahout.cf.taste.similarity.UserSimilarity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class RecommendServiceImpl implements RecommendService {

    @Autowired
    private CultureMapper cultureMapper;

    @Autowired
    private DataModel dataModel;
    //接口封装了对用户物品基本数据部分，提供了获得对用户物品内容的分析基本要求

    @Override
    public List<Culture> getRecommendItemsByUser(Long userId, int howMany) {

        List<Culture> list = new ArrayList<>();
        try {
            //similarity定义相似度算法的接口 计算相似度，相似度算法有很多种，采用基于曼哈顿距离相关性的相似度
            UserSimilarity similarity = new CityBlockSimilarity(dataModel);
            //neighborhood邻域 定义近邻算法的接口
            //计算最近邻域，邻居有两种算法，基于固定数量的邻居和基于相似度的邻居，这里使用基于固定数量的邻居
            UserNeighborhood userNeighborhood = new NearestNUserNeighborhood(howMany, similarity, dataModel);
            //recommender定义推荐算法接口 构建推荐器，基于用户的协同过滤推荐
            Recommender recommender = new GenericUserBasedRecommender(dataModel, userNeighborhood, similarity);
            long start = System.currentTimeMillis();
            //推荐文化
            List<RecommendedItem> recommendedItemList = recommender.recommend(userId, howMany);
            List<Long> itemIds = new ArrayList<Long>();
            for (RecommendedItem recommendedItem : recommendedItemList) {
                System.out.println(recommendedItem);
                itemIds.add(recommendedItem.getItemID());
            }
            //根据id查询文化
            if (itemIds != null && itemIds.size() > 0) {
                list = cultureMapper.findAllByIds(itemIds);
            } else {
                list = new ArrayList<>();
            }
        } catch (TasteException e) {
            // 协同过滤异常（如用户无行为/数据过少）时返回空列表，由上层用热门内容兜底
            e.printStackTrace();
            return new ArrayList<>();
        }
        return list;
    }
}
