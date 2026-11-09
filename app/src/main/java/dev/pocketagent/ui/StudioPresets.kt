package dev.pocketagent.ui

import dev.pocketagent.agent.AiClient

/** Local prompt recipes, not model-specific API capabilities. */
internal data class StudioTemplateField(val label: String, val example: String)
internal data class StudioTemplate(
    val id: String,
    val category: String,
    val title: String,
    val description: String,
    val size: String,
    val direction: String,
    val fields: List<StudioTemplateField>,
) {
    fun compose(values: List<String>): String {
        val details = fields.mapIndexedNotNull { index, field ->
            values.getOrNull(index)?.trim()?.takeIf { it.isNotEmpty() }?.let { "${field.label}：$it" }
        }
        return (listOf(direction) + details + "无水印。除非描述明确要求，否则不添加文字。").joinToString("\n")
    }
}

internal val studioTemplates = listOf(
    StudioTemplate("product", "商业设计", "商品棚拍", "用材质、灯光和留白呈现商品", "1024x1024",
        "制作一张商业商品摄影。产品为明确视觉中心，材质真实，边缘清晰，柔和棚拍光线，简洁背景，保留自然接触阴影。",
        listOf(StudioTemplateField("商品与材质", "哑光白色陶瓷香薰瓶、木质瓶盖"), StudioTemplateField("背景与配色", "暖米色背景、自然色系"), StudioTemplateField("构图与用途", "三分之四视角、居中、电商主图"))),
    StudioTemplate("poster", "商业设计", "品牌海报", "明确视觉焦点，为后期排版留白", "1024x1536",
        "设计一张品牌海报的主视觉底图。主体突出，视觉层级清楚，画面有张力，预留干净排版区域；不要生成标题或品牌标识，文字后期排版。",
        listOf(StudioTemplateField("主题与主体", "夏日柑橘气泡饮、透明玻璃杯"), StudioTemplateField("视觉风格", "清爽、明亮、自然日光"), StudioTemplateField("留白与配色", "上方留白、柑橘橙与浅蓝"))),
    StudioTemplate("portrait", "摄影", "人像写真", "控制环境、姿态和摄影质感", "1024x1536",
        "拍摄一张自然专业的人像写真。人物比例合理，皮肤纹理真实，眼神有表现力，景深和光线符合场景，避免过度磨皮。",
        listOf(StudioTemplateField("人物与服装", "成年女性、米色风衣"), StudioTemplateField("环境与光线", "雨后街道、傍晚柔光"), StudioTemplateField("姿态与镜头", "半身侧脸、平视、背景虚化"))),
    StudioTemplate("character", "插画", "角色设定", "定义外形、服装和画面风格", "1024x1536",
        "绘制一张精致的角色设定插画。全身完整入画，轮廓易读，服装细节统一，表情生动，背景不干扰角色主体。",
        listOf(StudioTemplateField("角色特征", "栗色短发、翠绿色眼睛、甜美微笑"), StudioTemplateField("服装与道具", "粉色魔法裙、白色翅膀装饰、星之杖"), StudioTemplateField("画风与氛围", "日系Q版、细腻线稿、温暖梦幻"))),
    StudioTemplate("editorial", "插画", "叙事插画", "把一个场景组织成有故事的画面", "1536x1024",
        "创作一张叙事插画。围绕故事建立主次关系，前中后景清楚，色彩协调，光线引导视线，细节服务于情绪。",
        listOf(StudioTemplateField("故事与场景", "小猫在雨天的书店里读信"), StudioTemplateField("风格与材质", "绘本插画、细腻水彩"), StudioTemplateField("情绪与配色", "温暖安静、琥珀色灯光与冷蓝窗景"))),
    StudioTemplate("food", "摄影", "美食大片", "用食物质感、器皿与光线营造食欲", "1024x1024",
        "拍摄一张专业美食摄影。食物质感自然，色泽真实，器皿和背景协调，柔和侧光突出层次，保持合理的食物结构。",
        listOf(StudioTemplateField("食物与器皿", "草莓奶油蛋糕、白色陶瓷盘"), StudioTemplateField("场景与光线", "窗边木桌、清晨自然侧光"), StudioTemplateField("构图与氛围", "近景、温暖精致、浅景深"))),
    StudioTemplate("film", "摄影", "电影感场景", "先设定叙事，再控制镜头与色彩", "1536x1024",
        "创作一张电影剧照风格的画面。构图具有叙事性，人物与环境关系明确，光源合理，色彩分级克制，保留真实材质与细腻影调。",
        listOf(StudioTemplateField("故事与人物", "深夜末班车上，一位旅人望向窗外"), StudioTemplateField("镜头与光线", "中景、车窗反射、街灯掠过"), StudioTemplateField("色彩与情绪", "低饱和蓝绿与暖黄、安静而期待"))),
    StudioTemplate("icon", "商业设计", "应用图标", "简洁轮廓与小尺寸可识别性", "1024x1024",
        "设计一枚用于应用商店展示的图标概念。主体单一，轮廓清楚，图形简洁，居中构图，小尺寸依然容易辨认；不添加设备外框或展示样机。",
        listOf(StudioTemplateField("应用用途与符号", "阅读应用、一页翻开的书"), StudioTemplateField("风格与材质", "简洁立体、柔和哑光材质"), StudioTemplateField("配色与背景", "深蓝主体、浅色纯净背景"))),
    StudioTemplate("fashion", "商业设计", "时尚大片", "服装、造型与布景形成统一视觉", "1024x1536",
        "创作一张高级时尚编辑摄影。服装结构和面料纹理清晰，人物比例自然，姿态有张力，造型与场景统一，不添加杂志标题或品牌标识。",
        listOf(StudioTemplateField("人物与服装", "成年模特、结构感黑色长外套"), StudioTemplateField("场景与造型", "极简混凝土空间、自然站姿"), StudioTemplateField("光线与调性", "高反差侧光、黑白影调"))),
    StudioTemplate("sticker", "插画", "表情贴纸", "清楚的动作、表情和干净边缘", "1024x1024",
        "绘制一张可爱角色贴纸插画。主体完整入画，表情和动作鲜明，粗细一致的清晰轮廓，简洁纯色背景，主体四周保留裁切余量。不承诺透明背景。",
        listOf(StudioTemplateField("角色与特征", "圆脸橘猫、短小四肢"), StudioTemplateField("动作与表情", "开心挥手、眼睛弯弯"), StudioTemplateField("画风与配色", "扁平插画、奶油橙与暖白"))),
    StudioTemplate("material", "空间设计", "材质研究", "用微观细节检验表面与照明方案", "1024x1024",
        "制作一张材质研究概念图。突出表面的纹理、粗糙度与光照关系，细节尺度一致，物理观感自然，背景不抢夺注意力。此图为视觉概念，不是可平铺贴图或实测材质。",
        listOf(StudioTemplateField("材质与细节", "磨砂金属、细密拉丝"), StudioTemplateField("形体与视角", "折弯金属薄片、微距局部"), StudioTemplateField("照明与配色", "大面积柔光、冷银色"))),
    StudioTemplate("space", "空间设计", "室内概念", "用空间布局、材质与采光表达方案", "1536x1024",
        "制作一张室内空间设计概念效果图。空间尺度合理，透视一致，材质自然，家具布局可读，采光真实。此图用于概念表达，不作为施工图。",
        listOf(StudioTemplateField("空间与功能", "小户型客厅、阅读与会客"), StudioTemplateField("风格与材质", "日式简约、浅木与亚麻"), StudioTemplateField("视角与采光", "入口广角视角、侧窗自然光"))),
)

internal fun studioTemplateChoices(category: String, query: String): List<StudioTemplate> =
    studioTemplates.filter { template ->
        (category == "全部" || template.category == category) &&
            listOf(template.title, template.description, template.category, template.direction)
                .any { it.contains(query.trim(), ignoreCase = true) }
    }

/** Standard DALL-E dimensions; custom providers still decide which sizes they accept. */
internal fun studioTemplateSize(size: String, model: String): String = when {
    Regex("(?:^|/)dall-e-2$", RegexOption.IGNORE_CASE).containsMatchIn(model.trim()) -> "1024x1024"
    Regex("(?:^|/)dall-e-3$", RegexOption.IGNORE_CASE).containsMatchIn(model.trim()) -> when (size) {
        "1024x1536" -> "1024x1792"
        "1536x1024" -> "1792x1024"
        else -> size
    }
    else -> size
}

/** Name-based candidates only: the provider still determines endpoint access and billing. */
internal fun isStudioImageModel(model: String): Boolean {
    val name = model.trim()
    if (Regex("(?:^|[-_./])(?:embedding|embeddings|caption|captioner|captioning|classification|upscale|upscaler)(?:$|[-_./0-9])", RegexOption.IGNORE_CASE).containsMatchIn(name)) return false
    return AiClient.isImageGenerationModel(name) ||
        Regex("(?:^|[/])(?:dall-e(?:$|[-_0-9])|(?:flux|sdxl|stable-diffusion|ideogram|recraft|midjourney|seedream|doubao-seedream)(?:$|[-_./0-9]))", RegexOption.IGNORE_CASE).containsMatchIn(name)
}

internal fun studioModelChoices(models: List<String>, query: String, showAll: Boolean): List<String> =
    models.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        .filter { (showAll || isStudioImageModel(it)) && it.contains(query.trim(), ignoreCase = true) }
        .sortedWith(String.CASE_INSENSITIVE_ORDER)
