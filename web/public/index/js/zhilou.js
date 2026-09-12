
$(function () {

    //查看详情
    $("body").on("click", ".look", function () {
        currentTop = $(this).parents(".item").offset().top - 50; //记录纸片的位置
        $(".footerDiv,.contentTwo,.PageTitle").hide();
        $(".topbar").attr("class", "topbarhident").hide(); //隐藏头部

        var parentsObj = $(this).parents(".item");
        var userName = $.trim($(parentsObj).find(".zhipian_name").text());
        var htmlStr = "<img src='" + $(parentsObj).find(".userInfo img").attr("src") + "' />";
        htmlStr += "<div class=\"user_R box\">";
        htmlStr += "<div class=\"zhipian_name\">" + userName + "</div>";
        htmlStr += "<div class=\"zhipian_time\">" + $(parentsObj).find(".zhipian_time").html() + "</div>";
        htmlStr += "</div>";
        $(".showZhiPian .userInfo").html(htmlStr);
        var content = $(parentsObj).find(".zhipian").attr("content").replace(new RegExp("hgl_single", 'g'), "\'").replace(new RegExp("hgl_double", 'g'), "\"");
        $(".showZhiPian .zhipian").html(content);
        $(".showZhiPian").attr("show", "1").show(); //标识为打开




    });

    //详情关闭
    $(".showZhiPian .close").click(function () {
        $(".showZhiPian").attr("show", "0").hide(); //标识为关闭
        $(".footerDiv,.contentTwo,.PageTitle").show();
        $(".topbarhident").attr("class", "topbar").show();

        $('body,html').scrollTop(currentTop);
        $(".showZhiPian .share").remove(); //移除分享按钮

    });
});

