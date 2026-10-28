"""Reproducible metadata-only inventory; never grants approval or copies source PDFs."""

import json
import re
from datetime import UTC, datetime

from app.config import REPO_ROOT, Settings
from app.rag.loader import load_pages, verified_path
from app.rag.models import DocumentSpec


def main():
    settings = Settings()
    manifest = json.loads(settings.manifest_path.read_text(encoding="utf-8"))
    records = []
    for item in manifest["documents"]:
        doc = DocumentSpec.model_validate(item)
        path = verified_path(settings.pdf_dir, doc)
        pages = load_pages(path)
        text = "\n".join(page.text for page in pages)
        chinese = len(re.findall(r"[\u3400-\u9fff]", text))
        characters = sum(len(p.text) for p in pages)
        records.append(
            {
                **doc.model_dump(),
                "sizeBytes": path.stat().st_size,
                "pageCount": len(pages),
                "textPages": sum(bool(p.text) for p in pages),
                "extractedCharacters": characters,
                "chineseCharacters": chinese,
                "language": "无文字"
                if not characters
                else "中文为主"
                if chinese / characters > 0.4
                else "含中文"
                if chinese
                else "英文为主",
                "parsingStatus": "NO_EXTRACTABLE_TEXT" if not characters else "TEXT_EXTRACTED",
            }
        )
    report = {
        "measuredAt": datetime.now(UTC).isoformat(),
        "source": "approved-manifest-and-real-pypdf",
        "originalFilesCopied": False,
        "documents": records,
    }
    output = REPO_ROOT / "docs" / "evals" / "R004-pdf-inventory.json"
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    lines = [
        "# R004 PDF实际清单",
        "",
        f"核查时间：{report['measuredAt']}。本机按已批准清单逐份核对SHA256并以当前pypdf/fonttools解析；完整元数据见[JSON证据](evals/R004-pdf-inventory.json)。",
        "",
        "用户已明确授权这24份文件用于本项目本地入库及公开范围引用。public是文档范围，仍须MES登录和内部签名；原件不复制进仓库、不提供匿名下载。版本仅采用可确认内容，其余标记unknown。它们包含政策、标准、ERP说明、科研和安全教学参考，不能宣传为本工厂伺服控制器专用SOP。",
        "",
        "| 文件 | 字节 | 物理页 | 文字页 | 字符/中文 | 语言 | 版本 | 解析 |",
        "| --- | ---: | ---: | ---: | ---: | --- | --- | --- |",
    ]
    for d in records:
        lines.append(
            f"| {d['filename']} | {d['sizeBytes']} | {d['pageCount']} | {d['textPages']} | {d['extractedCharacters']}/{d['chineseCharacters']} | {d['language']} | {d['version']} | {d['parsingStatus']} |"
        )
    lines.extend(
        [
            "",
            "23份可提取文字进入向量索引，共1635块；OSHA_3133共45页无可提取文字，登记后未建立可搜索块，OCR尚未实现。图表、图片和个别空白/扫描页没有被自动补全。ERPNext_Production_Analytics仅246字符，覆盖范围很窄，不能称作完整统计业务手册。",
            "",
            "切块只保留PDF物理1起页码。例：2024标准体系指南正文印刷第1页是PDF物理第3页；引用统一写物理页。仅有可读文本不意味着原件全部图表已解析。",
            "",
            "重现命令：`uv run --project agent python -m app.inventory`。新增文档必须另外核对内容、授权和清单；本命令不自动批准或入库。",
        ]
    )
    (REPO_ROOT / "docs" / "R004-PDF清单.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(
        json.dumps(
            {
                "documentCount": len(records),
                "textDocuments": sum(d["textPages"] > 0 for d in records),
                "scanOnlyDocuments": sum(d["textPages"] == 0 for d in records),
                "metadataOnly": True,
            }
        )
    )


if __name__ == "__main__":
    main()
