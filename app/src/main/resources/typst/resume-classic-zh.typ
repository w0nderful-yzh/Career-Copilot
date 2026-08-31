// classic-zh：Career Copilot 简历中文模板
// 数据由 TypstCompiler 写入同目录 content.json；字符串一律按字面渲染，不做转义
// （设计决策：免转义层与注入面）。
// 字段访问一律用 .at(key, default: "")：解析 JSON 可能缺键，模板缺字段时静默留空而非编译失败。

#let font-main = ("Noto Sans CJK SC", "PingFang SC")
#let color-primary = rgb("#1a3a5c")
#let color-muted = rgb("#555555")
#let color-line = rgb("#c8ccd0")

// 防御性取值：字典缺键返回默认值（嵌套结构逐层兜底）
#let at = (dict, key) => if dict == none { "" } else { dict.at(key, default: "") }
#let str-of = value => if value == none { "" } else { value }

// 段落头：左侧标题加粗，右侧日期右对齐
// 注意：参数名不能叫 left/right（会遮蔽 Typst 内置对齐常量导致 unexpected argument）
#let entry-header(title-part, date-part) = grid(
  columns: (1fr, auto),
  column-gutter: 1em,
  align: (left, right),
  title-part,
  date-part,
)

#let section-title(title) = {
  v(0.6em)
  text(size: 12pt, weight: "bold", fill: color-primary, tracking: 0.05em, title)
  v(-0.7em)
  line(length: 100%, stroke: 0.5pt + color-line)
  v(0.15em)
}

#let bullet-list(items) = {
  if items != none and items .len() > 0 {
    set list(indent: 0em, body-indent: 0.6em, marker: [–], spacing: 0.9em)
    set par(justify: true)
    // code 块内数组展开为 markup 需 join()，spread 语法只在 markup 模式可用
    items.map(it => list(str-of(it))).join()
  }
}

#set page(paper: "a4", margin: (top: 1.4cm, bottom: 1.4cm, x: 1.6cm))
#set text(font: font-main, size: 10pt, lang: "zh", region: "cn")
#set par(justify: true, leading: 0.72em)

// ===== 基本信息头 =====
#let data = json("content.json")
#let basic = at(data, "basicInfo")
#align(center)[
  #text(size: 20pt, weight: "bold", fill: color-primary, at(basic, "name"))
  #v(0.3em)
  #text(size: 9.5pt, fill: color-muted)[
    #at(basic, "jobIntention")
    #if at(basic, "phone") != "" [ ｜ #at(basic, "phone")]
    #if at(basic, "email") != "" [ ｜ #at(basic, "email")]
    #if at(basic, "location") != "" [ ｜ #at(basic, "location")]
  ]
]
#v(0.2em)
#line(length: 100%, stroke: 1.2pt + color-primary)

// ===== 教育背景 =====
#let education = at(data, "education")
#if education != none and education .len() > 0 {
  section-title("教育背景")
  for edu in education [
    #entry-header(
      [#text(weight: "bold", at(edu, "school")) #h(0.5em) #at(edu, "major") #h(0.5em) #at(edu, "degree")],
      [#at(edu, "startDate") -- #at(edu, "endDate")],
    )
    #if at(edu, "description") != "" [#text(size: 9.5pt, fill: color-muted, at(edu, "description"))]
  ]
}

// ===== 工作经历 =====
#let experience = at(data, "experience")
#if experience != none and experience .len() > 0 {
  section-title("工作经历")
  for exp in experience [
    #entry-header(
      [#text(weight: "bold", at(exp, "company")) #h(0.5em) #at(exp, "position")],
      [#at(exp, "startDate") -- #at(exp, "endDate")],
    )
    #bullet-list(at(exp, "bullets"))
  ]
}

// ===== 项目经历 =====
#let projects = at(data, "projects")
#if projects != none and projects .len() > 0 {
  section-title("项目经历")
  for proj in projects [
    #entry-header(
      [#text(weight: "bold", at(proj, "name")) #h(0.5em) #text(fill: color-muted, at(proj, "role"))],
      [#at(proj, "startDate") -- #at(proj, "endDate")],
    )
    #if at(proj, "techStack") != "" [#text(size: 9pt, fill: color-muted)[技术栈：#at(proj, "techStack")]]
    #bullet-list(at(proj, "bullets"))
  ]
}

// ===== 专业技能 =====
#let skills = at(data, "skills")
#if skills != none and skills .len() > 0 {
  section-title("专业技能")
  for skill in skills [
    #if at(skill, "content") != "" [
      #grid(
        columns: (5.5em, 1fr),
        column-gutter: 0.5em,
        text(weight: "bold", at(skill, "category")),
        at(skill, "content"),
      )
    ]
  ]
}

// ===== 自定义段（证书奖项 / 求职意向 / 个人简介等非标准段兜底） =====
#let custom-sections = at(data, "customSections")
#if custom-sections != none and custom-sections .len() > 0 {
  for custom in custom-sections {
    let items = at(custom, "items")
    if items != none and items .len() > 0 {
      section-title(at(custom, "title"))
      bullet-list(items)
    }
  }
}
