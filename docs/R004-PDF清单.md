# R004 PDF实际清单

核查时间：2026-10-27T16:29:14.621528+00:00。本机按已批准清单逐份核对SHA256并以当前pypdf/fonttools解析；完整元数据见[JSON证据](evals/R004-pdf-inventory.json)。

用户已明确授权这24份文件用于本项目本地入库及公开范围引用。public是文档范围，仍须MES登录和内部签名；原件不复制进仓库、不提供匿名下载。版本仅采用可确认内容，其余标记unknown。它们包含政策、标准、ERP说明、科研和安全教学参考，不能宣传为本工厂伺服控制器专用SOP。

| 文件 | 字节 | 物理页 | 文字页 | 字符/中文 | 语言 | 版本 | 解析 |
| --- | ---: | ---: | ---: | ---: | --- | --- | --- |
| 01_智能制造典型场景参考指引_2025年版.pdf | 242095 | 13 | 13 | 6259/5200 | 中文为主 | 2025年版 | TEXT_EXTRACTED |
| 02_国家智能制造标准体系建设指南_2024版.pdf | 2702030 | 36 | 36 | 14777/11582 | 中文为主 | 2024版 | TEXT_EXTRACTED |
| 03_制造业企业数字化转型实施指南.pdf | 1153116 | 22 | 22 | 10953/9248 | 中文为主 | unknown | TEXT_EXTRACTED |
| 04_制造业质量管理数字化实施指南_试行.pdf | 274523 | 10 | 10 | 5402/4708 | 中文为主 | 试行 | TEXT_EXTRACTED |
| 05_十四五智能制造发展规划.pdf | 351916 | 12 | 12 | 9560/8008 | 中文为主 | unknown | TEXT_EXTRACTED |
| 06_机械与电气工程学院实验室安全操作规程汇编_西昌学院.pdf | 1377693 | 5 | 5 | 4074/2818 | 中文为主 | unknown | TEXT_EXTRACTED |
| 07_工程训练中心学生指导手册_华侨大学.pdf | 381106 | 37 | 37 | 7947/6298 | 中文为主 | unknown | TEXT_EXTRACTED |
| 08_数控车工操作规程_武汉职业类院校.pdf | 696014 | 30 | 30 | 15136/7761 | 中文为主 | unknown | TEXT_EXTRACTED |
| ADB_Volume_1_精密工程与机械加工.pdf | 1480195 | 59 | 58 | 98676/0 | 英文为主 | unknown | TEXT_EXTRACTED |
| ADB_Volume_2_机电一体化技术.pdf | 2808898 | 68 | 67 | 96437/0 | 英文为主 | unknown | TEXT_EXTRACTED |
| ERPNext_Bill_of_Materials.pdf | 1493768 | 10 | 10 | 13017/0 | 英文为主 | unknown | TEXT_EXTRACTED |
| ERPNext_Manufacturing.pdf | 47434 | 1 | 1 | 1328/0 | 英文为主 | unknown | TEXT_EXTRACTED |
| ERPNext_Production_Analytics.pdf | 112590 | 1 | 1 | 246/0 | 英文为主 | unknown | TEXT_EXTRACTED |
| ERPNext_Quality_Review.pdf | 268075 | 2 | 2 | 1555/0 | 英文为主 | unknown | TEXT_EXTRACTED |
| ERPNext_Work_Order.pdf | 1196834 | 10 | 10 | 12102/0 | 英文为主 | unknown | TEXT_EXTRACTED |
| NIST_AMS_200-1_Smart_Manufacturing_Test_Bed_Design.pdf | 1553663 | 37 | 37 | 87703/0 | 英文为主 | unknown | TEXT_EXTRACTED |
| NIST_AMS_200-2.pdf | 1446924 | 53 | 53 | 98247/0 | 英文为主 | unknown | TEXT_EXTRACTED |
| NIST_AMS_300-10_制造数据可信性与追溯.pdf | 2069605 | 44 | 44 | 88087/0 | 英文为主 | unknown | TEXT_EXTRACTED |
| NIST_AMS_300-11_制造数据采集与维护.pdf | 1015311 | 20 | 20 | 36890/0 | 英文为主 | unknown | TEXT_EXTRACTED |
| NIST_MESA_MOM_CMM_User_Guide.pdf | 69543 | 2 | 2 | 3686/0 | 英文为主 | unknown | TEXT_EXTRACTED |
| OSHA_3120_锁定挂牌.pdf | 492525 | 45 | 44 | 66488/0 | 英文为主 | unknown | TEXT_EXTRACTED |
| OSHA_3132_过程安全管理.pdf | 204074 | 59 | 59 | 91664/0 | 英文为主 | unknown | TEXT_EXTRACTED |
| OSHA_3133_过程安全管理合规指南.pdf | 4064963 | 45 | 0 | 0/0 | 无文字 | unknown | NO_EXTRACTABLE_TEXT |
| OSHA_3170_机械防护.pdf | 591876 | 60 | 58 | 174571/0 | 英文为主 | unknown | TEXT_EXTRACTED |

23份可提取文字进入向量索引，共1635块；OSHA_3133共45页无可提取文字，登记后未建立可搜索块，OCR尚未实现。图表、图片和个别空白/扫描页没有被自动补全。ERPNext_Production_Analytics仅246字符，覆盖范围很窄，不能称作完整统计业务手册。

切块只保留PDF物理1起页码。例：2024标准体系指南正文印刷第1页是PDF物理第3页；引用统一写物理页。仅有可读文本不意味着原件全部图表已解析。

重现命令：`uv run --project agent python -m app.inventory`。新增文档必须另外核对内容、授权和清单；本命令不自动批准或入库。
