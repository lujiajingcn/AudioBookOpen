package com.audiobook.server.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.imageio.IIOImage;
import javax.imageio.ImageReader;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从上传的电子书里提取一张图片作为书城封面（PDF 的**前 3 页** / EPUB 的**前 3 个 spine 章节**）。
 *
 * <h3>为什么要它</h3>
 * 书城封面有两条来源：管理员手工上传的 {@code store_books.cover_image}，以及按书名/作者现画的文字封面。
 * 但出版社排版的 PDF 首页往往自带封面图，上传后却没人再手工传一遍封面 —— 结果一本有现成封面的书，
 * 书城里显示的仍是纯色文字块。本类把「书里本来就有图」这件事自动用起来。
 *
 * <h3>为什么不渲染整页（{@code PDFRenderer}）</h3>
 * 渲染首页必然得到一张图，于是「书里有没有图片」这个判断就永远为真，文字封面分支成了死代码 ——
 * 而扫描版 PDF 渲染出来的往往是带页眉页码的正文页，比文字封面更难看。所以这里只取**内嵌图片**。
 *
 * <h3>取舍</h3>
 * 候选图要求「够大」且「不是近似纯色」：PDF 里到处是小图标、分隔线、背景色块，
 * 直接取第一张会得到 24x24 的装饰图。多候选时取像素面积最大的一张。
 * 输出统一转 RGB、最长边压到 {@value #MAX_OUTPUT_SIDE}px、编码为 JPEG
 * （书城的封面卡片刻画尺寸很小，原图动辄几 MB，直接落盘只是浪费磁盘和带宽）。
 *
 * <p>MOBI 未实现：正文是 PalmDoc 记录拼接，图片要靠 ``<img recindex>`` 反查记录号，
 * 索引映射极易出错且书城当前没有 MOBI 书 —— 落到文字封面即可，不做半成品。
 * TXT 本体不可能有图。
 */
@Component
public class BookCoverExtractor {

    private static final Logger log = LoggerFactory.getLogger(BookCoverExtractor.class);

    /** 只看电子书开头的这几页 / 这几个 spine 章节（需求即「前 3 页」） */
    static final int HEAD_UNITS = 3;

    /** 候选图最小边长：更小的基本是图标、项目符号、分隔线 */
    static final int MIN_SIDE = 150;

    /** 候选图最小像素面积（约 200x300） */
    static final long MIN_AREA = 60_000L;

    /** 候选图片节最小体积：小于此值不可能是张真图（JPEG/PNG 头部就有几十字节） */
    static final int MIN_BYTES = 2048;

    /**
     * 候选图最大像素面积（v5 / S3）。
     *
     * <p><b>为什么必须有上限</b>：只有下限时，PDF 侧会按**声明宽高**过筛后就 `image.getImage()`
     * 全量解码，EPUB 侧直接 `ImageIO.read(bytes)`。一张 40000×40000 的图按 INT_RGB 是 6.4 GB，
     * 单张就能把服务打到 OOM —— 而这类图在恶意/畸形电子书里是现成的攻击面。
     *
     * <p><b>16M 的取值依据</b>：合法封面的最大量级是 PDF 300dpi 整页（A4 ≈ 8.7M px），
     * 输出反正会缩到 {@link #MAX_OUTPUT_SIDE}；16M（≈ 4000×4000）留了近一倍余量，
     * 同时把单张解码峰值压到约 64 MB（INT_RGB）。
     */
    static final long MAX_AREA = 16_000_000L;

    /** 输出图最长边上限 */
    static final int MAX_OUTPUT_SIDE = 800;

    /** 近似纯色判定的通道容差（0~255）。纯白/纯色页面没有信息量，不如用文字封面 */
    private static final int UNIFORM_TOLERANCE = 8;

    /** 近似纯色的抽样网格边长 */
    private static final int UNIFORM_GRID = 32;

    /** 递归进 Form XObject 的最大层数（图片常被包在表单对象里） */
    private static final int MAX_FORM_DEPTH = 2;

    private static final float JPEG_QUALITY = 0.85f;

    /** `<img src="...">` */
    private static final Pattern IMG_SRC =
            Pattern.compile("(?i)<img\\b[^>]*?\\bsrc\\s*=\\s*[\"']([^\"']+)[\"']");

    /** `<image xlink:href="...">`（EPUB 里用内联 SVG 放封面图也很常见） */
    private static final Pattern SVG_IMAGE_HREF =
            Pattern.compile("(?i)<image\\b[^>]*?\\b(?:xlink:)?href\\s*=\\s*[\"']([^\"']+)[\"']");

    private final BookTextExtractor textExtractor;

    public BookCoverExtractor(BookTextExtractor textExtractor) {
        this.textExtractor = textExtractor;
    }

    /** 提取结果：已编码好的图片字节 + 扩展名（当前恒为 jpg） */
    public record CoverImage(byte[] data, String extension) {
        public String fileNameSuffix() {
            return "." + extension;
        }
    }

    /**
     * 尝试从电子书里取一张封面图。
     *
     * <p><b>不抛异常</b>：封面是附加信息，取不到 / 取失败都只记日志并返回空，
     * 由调用方退回「书名 + 作者」文字封面。上传流程不该因为封面解析失败而失败。
     *
     * @param file      已落盘的电子书
     * @param extension 小写扩展名（pdf / epub / txt / mobi）
     */
    public Optional<CoverImage> extract(File file, String extension) {
        if (file == null || !file.isFile() || extension == null) {
            return Optional.empty();
        }
        String ext = extension.toLowerCase(Locale.ROOT);
        try {
            Optional<BufferedImage> picked = switch (ext) {
                case "pdf" -> fromPdf(file);
                case "epub" -> fromEpub(file);
                default -> Optional.empty();
            };
            if (picked.isEmpty()) {
                log.info("书里前 {} 页没有可用图片，该书将使用书名+作者封面: {}", HEAD_UNITS, file.getName());
                return Optional.empty();
            }
            CoverImage cover = encode(picked.get());
            log.info("已用书里的图片作封面: {}（{} 字节）", file.getName(), cover.data().length);
            return Optional.of(cover);
        } catch (Exception e) {
            log.warn("从书里提取封面图失败（该书将使用书名+作者封面）: {}（{}）", file.getName(), e.getMessage());
            return Optional.empty();
        }
    }

    // ---------- PDF ----------

    private Optional<BufferedImage> fromPdf(File file) throws IOException {
        try (PDDocument document = Loader.loadPDF(file)) {
            int pages = Math.min(HEAD_UNITS, document.getNumberOfPages());
            BufferedImage best = null;
            long bestArea = 0;
            for (int i = 0; i < pages; i++) {
                PDPage page = document.getPage(i);
                PDResources resources = page.getResources();
                if (resources == null) {
                    continue;
                }
                Map<COSName, PDImageXObject> images = new HashMap<>();
                collectImages(resources, 0, images);
                for (PDImageXObject image : images.values()) {
                    long area = (long) image.getWidth() * image.getHeight();
                    if (!isCandidateSize(image.getWidth(), image.getHeight(), area) || area <= bestArea) {
                        continue;
                    }
                    BufferedImage decoded;
                    try {
                        decoded = image.getImage();
                    } catch (Exception e) {
                        // 损坏/不支持的色彩空间（如 JPX、Devicen）不该毁掉整本书的封面提取
                        log.debug("跳过无法解码的 PDF 图片: {}（{}）", image.getCOSObject().toString(), e.getMessage());
                        continue;
                    }
                    if (decoded == null || isNearlyUniform(decoded)) {
                        continue;
                    }
                    best = decoded;
                    bestArea = area;
                }
            }
            return Optional.ofNullable(best);
        }
    }

    /** 收集一页里的图片 XObject（含被 Form XObject 包住的，深度受限） */
    private void collectImages(PDResources resources, int depth, Map<COSName, PDImageXObject> out) {
        if (resources == null || depth > MAX_FORM_DEPTH) {
            return;
        }
        for (COSName name : resources.getXObjectNames()) {
            PDXObject xObject;
            try {
                xObject = resources.getXObject(name);
            } catch (IOException e) {
                log.debug("跳过无法读取的 XObject {}: {}", name.getName(), e.getMessage());
                continue;
            }
            if (xObject instanceof PDImageXObject image) {
                if (!image.isStencil()) {
                    // 掩膜（isStencil）只是给别的图当透明度用的，单独拿出来是黑白剪影
                    out.putIfAbsent(name, image);
                }
            } else if (xObject instanceof PDFormXObject form) {
                collectImages(form.getResources(), depth + 1, out);
            }
        }
    }

    private boolean isCandidateSize(int width, int height, long area) {
        return width >= MIN_SIDE && height >= MIN_SIDE
                && area >= MIN_AREA && area <= MAX_AREA;
    }

    // ---------- EPUB ----------

    private Optional<BufferedImage> fromEpub(File file) throws IOException {
        // 复用 BookTextExtractor 的加固读取（ZIP 炸弹三重上限 + XXE 加固），不另造一套
        Map<String, byte[]> entries = textExtractor.readZipEntries(file);
        if (entries.isEmpty()) {
            return Optional.empty();
        }
        byte[] container = textExtractor.getZipEntry(entries, "META-INF/container.xml");
        if (container == null) {
            return Optional.empty();
        }
        String opfPath = textExtractor.parseContainerXml(container);
        byte[] opf = textExtractor.getZipEntry(entries, opfPath);
        if (opf == null) {
            log.warn("EPUB 缺少 OPF 文件，跳过封面提取: {}", opfPath);
            return Optional.empty();
        }
        String opfDir = opfPath.contains("/") ? opfPath.substring(0, opfPath.lastIndexOf('/') + 1) : "";

        // 1) OPF 里明确声明的封面图（EPUB3 的 properties="cover-image" / EPUB2 的 <meta name="cover">）
        String declared = declaredCoverHref(opf, opfDir);
        if (declared != null) {
            BufferedImage image = pickFromEntry(entries, declared);
            if (image != null) {
                return Optional.of(image);
            }
            log.info("EPUB 声明的封面不可用，回退去看前 {} 个章节的插图: {}", HEAD_UNITS, declared);
        }

        // 2) 前几个 spine 章节里的 <img>：多候选取「图片字节最大」的那张
        Optional<BufferedImage> best = Optional.empty();
        long bestBytes = MIN_BYTES;
        var spine = textExtractor.parseOpfSpine(opf, opfDir);
        for (int i = 0; i < Math.min(HEAD_UNITS, spine.size()); i++) {
            String docPath = spine.get(i);
            byte[] docData = textExtractor.getZipEntry(entries, docPath);
            if (docData == null) {
                continue;
            }
            String dir = docPath.contains("/") ? docPath.substring(0, docPath.lastIndexOf('/') + 1) : "";
            for (String src : imageRefs(new String(docData, StandardCharsets.UTF_8))) {
                String path = textExtractor.normalizeZipPath(dir + decodeUrl(src));
                byte[] data = textExtractor.getZipEntry(entries, path);
                if (data == null || data.length <= bestBytes) {
                    continue;
                }
                BufferedImage image = decode(data);
                if (image == null || !hasEnoughPixels(image) || isNearlyUniform(image)) {
                    continue;
                }
                best = Optional.of(image);
                bestBytes = data.length;
            }
        }
        return best;
    }

    /** 读一个 ZIP 条目并解码为图片（不满足尺寸要求返回 null） */
    private BufferedImage pickFromEntry(Map<String, byte[]> entries, String path) {
        byte[] data = textExtractor.getZipEntry(entries, path);
        if (data == null || data.length < MIN_BYTES) {
            return null;
        }
        BufferedImage image = decode(data);
        if (image == null || !hasEnoughPixels(image) || isNearlyUniform(image)) {
            return null;
        }
        return image;
    }

    /** 解析 OPF：返回封面图在 ZIP 内的完整路径；没声明返回 null */
    private String declaredCoverHref(byte[] opf, String opfDir) {
        try {
            Document doc = textExtractor.createSafeDocumentBuilder()
                    .parse(new ByteArrayInputStream(textExtractor.stripDoctype(opf)));

            Map<String, String> manifest = new HashMap<>();
            NodeList items = doc.getElementsByTagName("item");
            for (int i = 0; i < items.getLength(); i++) {
                Element item = (Element) items.item(i);
                String id = item.getAttribute("id");
                String href = item.getAttribute("href");
                if (id.isEmpty() || href.isEmpty()) {
                    continue;
                }
                // EPUB3：直接在 manifest 项上标了 properties="cover-image"
                String properties = item.getAttribute("properties");
                if (properties != null && properties.contains("cover-image")) {
                    return textExtractor.normalizeZipPath(opfDir + decodeUrl(href));
                }
                manifest.put(id, href);
            }

            // EPUB2：<meta name="cover" content="<manifest id>"/>
            NodeList metas = doc.getElementsByTagName("meta");
            for (int i = 0; i < metas.getLength(); i++) {
                Element meta = (Element) metas.item(i);
                if ("cover".equalsIgnoreCase(meta.getAttribute("name"))) {
                    String id = meta.getAttribute("content");
                    String href = manifest.get(id);
                    if (href != null && !href.isEmpty()) {
                        return textExtractor.normalizeZipPath(opfDir + decodeUrl(href));
                    }
                }
            }
        } catch (Exception e) {
            log.debug("解析 EPUB OPF 封面声明失败（将退回扫描章节插图）: {}", e.getMessage());
        }
        return null;
    }

    private java.util.List<String> imageRefs(String xhtml) {
        java.util.List<String> refs = new java.util.ArrayList<>();
        Matcher m = IMG_SRC.matcher(xhtml);
        while (m.find()) {
            refs.add(m.group(1));
        }
        Matcher svg = SVG_IMAGE_HREF.matcher(xhtml);
        while (svg.find()) {
            refs.add(svg.group(1));
        }
        return refs;
    }

    private String decodeUrl(String href) {
        try {
            return URLDecoder.decode(href, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // href 里出现非法 % 转义（手工拼的 EPUB 常见）时按原样使用
            return href;
        }
    }

    // ---------- 通用：判定与编码 ----------

    private boolean hasEnoughPixels(BufferedImage image) {
        long area = (long) image.getWidth() * image.getHeight();
        return image.getWidth() >= MIN_SIDE && image.getHeight() >= MIN_SIDE
                && area >= MIN_AREA && area <= MAX_AREA;
    }

    /** 抽样看是否近似纯色（纯白页、纯色背景块都没有当封面的价值） */
    private boolean isNearlyUniform(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        int minR = 255, maxR = 0, minG = 255, maxG = 0, minB = 255, maxB = 0;
        for (int i = 0; i < UNIFORM_GRID; i++) {
            for (int j = 0; j < UNIFORM_GRID; j++) {
                int x = Math.min(w - 1, (int) ((i + 0.5) * w / UNIFORM_GRID));
                int y = Math.min(h - 1, (int) ((j + 0.5) * h / UNIFORM_GRID));
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                minR = Math.min(minR, r);
                maxR = Math.max(maxR, r);
                minG = Math.min(minG, g);
                maxG = Math.max(maxG, g);
                minB = Math.min(minB, b);
                maxB = Math.max(maxB, b);
            }
        }
        return maxR - minR <= UNIFORM_TOLERANCE
                && maxG - minG <= UNIFORM_TOLERANCE
                && maxB - minB <= UNIFORM_TOLERANCE;
    }

    /**
     * 解码图片，**超面积上限的直接不解码**（v5 / S3）。
     *
     * <p>先读图片头拿宽高：`ImageIO.read` 是全量解码，等拿到 BufferedImage 再判就晚了 ——
     * 峰值内存已经付出去了。少数 reader 读不出头信息（返回 null）时退回直接解码，
     * 由调用方的 {@link #hasEnoughPixels} 做兜底。
     */
    private BufferedImage decode(byte[] data) {
        int[] size = probeImageSize(data);
        if (size != null) {
            long area = (long) size[0] * size[1];
            if (area > MAX_AREA) {
                log.warn("跳过超大封面候选: {}x{}（面积 {} 超过上限 {}），避免解码撑爆堆",
                        size[0], size[1], area, MAX_AREA);
                return null;
            }
        }
        try {
            return ImageIO.read(new ByteArrayInputStream(data));
        } catch (Exception e) {
            return null;
        }
    }

    /** 只读图片头拿宽高（不解码像素）；格式不支持/头信息读不到时返回 null */
    private int[] probeImageSize(byte[] data) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            if (input == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                return new int[]{reader.getWidth(0), reader.getHeight(0)};
            } finally {
                reader.dispose();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** 归一化为 RGB（JPEG 不支持 alpha/索引色）→ 缩到上限 → 编码 JPEG */
    private CoverImage encode(BufferedImage source) throws IOException {
        BufferedImage rgb = toRgb(source);
        BufferedImage scaled = scaleDown(rgb);
        byte[] data = writeJpeg(scaled);
        return new CoverImage(data, "jpg");
    }

    private BufferedImage toRgb(BufferedImage source) {
        if (source.getType() == BufferedImage.TYPE_INT_RGB) {
            return source;
        }
        BufferedImage rgb = new BufferedImage(source.getWidth(), source.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        try {
            // 先铺白底再画：带透明通道的 PNG 直接转 RGB 会把透明区变黑
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, source.getWidth(), source.getHeight());
            g.drawImage(source, 0, 0, null);
        } finally {
            g.dispose();
        }
        return rgb;
    }

    private BufferedImage scaleDown(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        int longest = Math.max(w, h);
        if (longest <= MAX_OUTPUT_SIDE) {
            return image;
        }
        double ratio = (double) MAX_OUTPUT_SIDE / longest;
        int nw = Math.max(1, (int) Math.round(w * ratio));
        int nh = Math.max(1, (int) Math.round(h * ratio));
        BufferedImage scaled = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(image, 0, 0, nw, nh, null);
        } finally {
            g.dispose();
        }
        return scaled;
    }

    private byte[] writeJpeg(BufferedImage image) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(JPEG_QUALITY);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(baos)) {
            writer.setOutput(out);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return baos.toByteArray();
    }
}
