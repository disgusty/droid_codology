package com.disgusty.droid_codology.lang;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/** The built-in languages and lookup by file name, extension or shebang. */
public final class Languages {
    private Languages() {
    }

    private static final ArrayList<Language> ALL = new ArrayList<Language>();
    private static final HashMap<String, Language> BY_ID = new HashMap<String, Language>();
    private static final HashMap<String, Language> BY_EXT = new HashMap<String, Language>();
    private static final HashMap<String, Language> BY_NAME = new HashMap<String, Language>();

    public static final Language PLAIN = new Language("plaintext", "Plain Text", "txt text log", "", null);

    // Shared keyword sets.
    private static final String C_CONTROL = "if else for while do switch case default break continue return goto";
    private static final String C_TYPES = "void char short int long float double signed unsigned bool _Bool size_t ssize_t "
            + "int8_t int16_t int32_t int64_t uint8_t uint16_t uint32_t uint64_t uintptr_t intptr_t ptrdiff_t wchar_t FILE";
    private static final String C_KW = "auto const extern register static volatile struct union enum typedef sizeof inline restrict _Atomic "
            + "_Noreturn _Static_assert _Thread_local alignas alignof";
    private static final String CPP_KW = C_KW + " class namespace template typename public private protected virtual override final "
            + "friend operator new delete this using explicit constexpr consteval constinit mutable noexcept static_assert "
            + "decltype concept requires co_await co_yield co_return export module import thread_local static_cast "
            + "dynamic_cast reinterpret_cast const_cast typeid";
    private static final String JS_KW = "var let const function class extends new delete typeof instanceof in of void this super "
            + "import export from as async await yield static get set debugger with";
    private static final String JS_CONTROL = "if else for while do switch case default break continue return throw try catch finally";
    private static final String JS_CONST = "true false null undefined NaN Infinity";
    private static final String TS_EXTRA = " interface type enum implements declare namespace module abstract private public "
            + "protected readonly keyof infer is asserts satisfies override accessor";
    private static final String TS_TYPES = "string number boolean any unknown never object symbol bigint void";

    static {
        reg(PLAIN);

        // ---- C family
        reg(new Language("c", "C", "c h", "", () -> CodeLexer.spec()
                .keywords(C_KW).control(C_CONTROL).types(C_TYPES).constants("NULL true false")
                .line("//").docBlock("/**", "*/").block("/*", "*/").preproc().build())
                .comments("//", "/*", "*/"));
        reg(new Language("cpp", "C++", "cpp cc cxx c++ hpp hh hxx h++ ino inl ipp tpp cu cuh", "", () -> CodeLexer.spec()
                .keywords(CPP_KW).control(C_CONTROL + " try catch throw").types(C_TYPES + " auto string vector map set nullptr_t")
                .constants("NULL nullptr true false").line("//").docBlock("/**", "*/").block("/*", "*/").preproc().build())
                .comments("//", "/*", "*/"));
        reg(new Language("objective-c", "Objective-C", "m mm", "", () -> CodeLexer.spec()
                .keywords(C_KW + " @interface @implementation @end @property @synthesize @protocol @class @selector @autoreleasepool self super nonatomic strong weak copy readonly")
                .control(C_CONTROL + " @try @catch @finally @throw").types(C_TYPES + " id BOOL NSString NSInteger CGFloat")
                .constants("nil YES NO NULL true false").line("//").block("/*", "*/").preproc().annotations().build())
                .comments("//", "/*", "*/"));
        reg(new Language("csharp", "C#", "cs csx", "", () -> CodeLexer.spec()
                .keywords("abstract as base checked class const delegate enum event explicit extern fixed implicit in interface internal is lock namespace new operator out override params private protected public readonly ref sealed sizeof stackalloc static struct this typeof unchecked unsafe using virtual volatile async await var dynamic get set init value record where yield partial global nameof when with required file scoped")
                .control("if else for foreach while do switch case default break continue return goto throw try catch finally")
                .types("bool byte sbyte char decimal double float int uint long ulong short ushort object string void nint nuint")
                .constants("true false null").line("//").docLine("///").block("/*", "*/").preproc().ml("\"\"\"").build())
                .comments("//", "/*", "*/"));
        reg(new Language("java", "Java", "java jav", "", () -> CodeLexer.spec()
                .keywords("abstract class extends implements interface enum final native new package import private protected public static strictfp synchronized this super throws transient volatile instanceof var record sealed permits non-sealed module requires exports opens uses provides with to transitive yield assert const")
                .control("if else for while do switch case default break continue return throw try catch finally goto")
                .types("boolean byte char short int long float double void String Object")
                .constants("true false null").line("//").docBlock("/**", "*/").block("/*", "*/").ml("\"\"\"").annotations().build())
                .comments("//", "/*", "*/"));
        reg(new Language("smali", "Smali", "smali", "", () -> CodeLexer.spec()
                .keywords(".class .super .source .implements .field .method .end .registers .locals .param .prologue .line .annotation .subannotation .enum .packed-switch .sparse-switch .array-data .catch .catchall public private protected static final abstract synthetic constructor bridge varargs native")
                .line("#").quotes("\"").dashes().noCapsTypes().build())
                .comments("#", null, null));
        reg(new Language("kotlin", "Kotlin", "kt kts", "", () -> CodeLexer.spec()
                .keywords("package import class interface fun val var object typealias constructor init companion data sealed enum annotation inner open abstract final override private protected public internal lateinit const suspend inline noinline crossinline reified operator infix tailrec external expect actual vararg by get set field it this super is as in out where value")
                .control("if else when for while do break continue return throw try catch finally")
                .types("Any Unit Nothing Int Long Short Byte Float Double Boolean Char String Array List Map Set")
                .constants("true false null").line("//").docBlock("/**", "*/").block("/*", "*/").ml("\"\"\"").annotations().dollar().build())
                .comments("//", "/*", "*/"));
        reg(new Language("scala", "Scala", "scala sc sbt", "", () -> CodeLexer.spec()
                .keywords("abstract case class def extends final implicit import lazy new object override package private protected sealed super this trait type val var with given using enum export then extension opaque inline transparent derives")
                .control("if else for while do match return throw try catch finally yield")
                .types("Int Long Double Float Boolean Char String Unit Any AnyRef Nothing Option List Map Seq")
                .constants("true false null None Nil").line("//").docBlock("/**", "*/").block("/*", "*/").ml("\"\"\"").annotations().build())
                .comments("//", "/*", "*/"));
        reg(new Language("groovy", "Groovy / Gradle", "groovy gradle gvy", "Jenkinsfile", () -> CodeLexer.spec()
                .keywords("def class interface enum extends implements import package new static final abstract public private protected this super in as trait var")
                .control("if else for while do switch case default break continue return throw try catch finally assert")
                .types("void boolean int long double float String Object List Map")
                .constants("true false null").line("//").docBlock("/**", "*/").block("/*", "*/").ml("'''", "\"\"\"").annotations().dollar().build())
                .comments("//", "/*", "*/"));
        reg(new Language("dart", "Dart", "dart", "", () -> CodeLexer.spec()
                .keywords("abstract as async await base class const covariant deferred dynamic enum export extends extension external factory final get hide implements import in interface is late library mixin new of on operator part required sealed set show static super sync this typedef var with yield")
                .control("if else for while do switch case default break continue return throw try catch finally rethrow assert")
                .types("void int double num bool String List Map Set Future Stream Object dynamic Never")
                .constants("true false null").line("//").docLine("///").docBlock("/**", "*/").block("/*", "*/").ml("'''", "\"\"\"").annotations().dollar().build())
                .comments("//", "/*", "*/"));
        reg(new Language("swift", "Swift", "swift", "", () -> CodeLexer.spec()
                .keywords("associatedtype class deinit enum extension fileprivate func import init inout internal let open operator private precedencegroup protocol public rethrows static struct subscript typealias var actor async await some any self Self super throws convenience dynamic final lazy mutating nonmutating optional override required unowned weak where is as in get set willSet didSet")
                .control("if else guard for while repeat switch case default break continue fallthrough return throw do catch defer")
                .types("Int Int8 Int16 Int32 Int64 UInt Double Float Bool String Character Array Dictionary Set Optional Void Any")
                .constants("true false nil").line("//").docLine("///").block("/*", "*/").ml("\"\"\"").annotations().build())
                .comments("//", "/*", "*/"));
        reg(new Language("go", "Go", "go", "", () -> CodeLexer.spec()
                .keywords("package import func var const type struct interface map chan go defer select range")
                .control("if else for switch case default break continue return goto fallthrough")
                .types("bool byte rune string int int8 int16 int32 int64 uint uint8 uint16 uint32 uint64 uintptr float32 float64 complex64 complex128 error any")
                .builtins("append cap close complex copy delete imag len make new panic print println real recover min max clear")
                .constants("true false nil iota").line("//").block("/*", "*/").ml("`").noCapsTypes().build())
                .comments("//", "/*", "*/").tabs(4, false));
        reg(new Language("rust", "Rust", "rs", "", () -> CodeLexer.spec()
                .keywords("as async await const crate dyn enum extern fn impl in let mod move mut pub ref self Self static struct super trait type union unsafe use where macro_rules")
                .control("if else for while loop match break continue return yield")
                .types("i8 i16 i32 i64 i128 isize u8 u16 u32 u64 u128 usize f32 f64 bool char str String Vec Option Result Box")
                .constants("true false None Some Ok Err").line("//").docLine("///").docLine("//!").block("/*", "*/").quotes("\"").build())
                .comments("//", "/*", "*/").pairs("([{\"", ")]}\""));
        reg(new Language("zig", "Zig", "zig", "", () -> CodeLexer.spec()
                .keywords("const var fn pub extern export inline comptime struct enum union error test usingnamespace defer errdefer try catch orelse anytype opaque packed align allowzero volatile threadlocal async await suspend resume nosuspend noalias")
                .control("if else for while switch break continue return unreachable")
                .types("i8 i16 i32 i64 u8 u16 u32 u64 usize isize f16 f32 f64 bool void type anyerror noreturn")
                .constants("true false null undefined").line("//").build())
                .comments("//", null, null));

        // ---- Web
        reg(new Language("javascript", "JavaScript", "js mjs cjs jsx es6", "", () -> CodeLexer.spec()
                .keywords(JS_KW).control(JS_CONTROL).constants(JS_CONST)
                .line("//").docBlock("/**", "*/").block("/*", "*/").quotes("\"'").ml("`").regex().dollar().build())
                .comments("//", "/*", "*/").pairs("([{\"'`", ")]}\"'`").tabs(2, true));
        reg(new Language("typescript", "TypeScript", "ts tsx mts cts", "", () -> CodeLexer.spec()
                .keywords(JS_KW + TS_EXTRA).control(JS_CONTROL).constants(JS_CONST).types(TS_TYPES)
                .line("//").docBlock("/**", "*/").block("/*", "*/").quotes("\"'").ml("`").regex().annotations().build())
                .comments("//", "/*", "*/").pairs("([{\"'`", ")]}\"'`").tabs(2, true));
        reg(new Language("json", "JSON", "json jsonc json5 webmanifest code-workspace babelrc eslintrc prettierrc", ".babelrc .eslintrc .prettierrc", () -> CodeLexer.spec()
                .constants("true false null").line("//").block("/*", "*/").quotes("\"").keys().noCalls().noCapsTypes().build())
                .comments("//", "/*", "*/").pairs("([{\"", ")]}\"").tabs(2, true));
        reg(new Language("html", "HTML", "html htm xhtml shtml vue svelte", "", () -> new MarkupLexer(true, find("javascript").lexer(), find("css").lexer()))
                .comments(null, "<!--", "-->").tabs(2, true));
        reg(new Language("xml", "XML", "xml xsd xsl xslt svg plist csproj vcxproj props targets resx xaml fxml rss atom wsdl iml kml gpx", "", () -> new MarkupLexer(false, null, null))
                .comments(null, "<!--", "-->").tabs(4, true));
        reg(new Language("css", "CSS", "css", "", () -> new StyleLexer(false))
                .comments(null, "/*", "*/").tabs(2, true));
        reg(new Language("scss", "SCSS", "scss sass", "", () -> new StyleLexer(true))
                .comments("//", "/*", "*/").tabs(2, true));
        reg(new Language("less", "Less", "less", "", () -> new StyleLexer(true))
                .comments("//", "/*", "*/").tabs(2, true));
        reg(new Language("php", "PHP", "php phtml php3 php4 php5 phps", "", () -> CodeLexer.spec().ignoreCase()
                .keywords("abstract and array as callable class clone const declare echo enddeclare endfor endforeach endif endswitch endwhile extends final fn function global goto implements include include_once instanceof insteadof interface isset list match namespace new or print private protected public readonly require require_once static trait unset use var xor yield enum")
                .control("if else elseif for foreach while do switch case default break continue return throw try catch finally")
                .types("int float bool string void mixed never object iterable self parent")
                .constants("true false null").line("//", "#").docBlock("/**", "*/").block("/*", "*/").dollar().annotations().build())
                .comments("//", "/*", "*/"));

        // ---- Scripting
        reg(new Language("python", "Python", "py pyw pyi pyx pxd gyp", "SConstruct SConscript", () -> CodeLexer.spec()
                .keywords("and as assert async await class def del from global import in is lambda nonlocal not or pass with yield type match case")
                .control("if elif else for while break continue return raise try except finally")
                .types("int float str bool list dict set tuple bytes object complex frozenset bytearray")
                .builtins("print len range open input super isinstance enumerate zip map filter sorted min max sum abs any all")
                .constants("True False None self cls").line("#").ml("\"\"\"", "'''").annotations().build())
                .comments("#", null, null).indentAfter(":").offSide().shebang("python", "python3", "python2"));
        reg(new Language("ruby", "Ruby", "rb rbw rake gemspec ru erb", "Rakefile Gemfile Podfile Vagrantfile Fastfile Brewfile", () -> CodeLexer.spec()
                .keywords("alias and begin class def defined? do end ensure in module next nil not or redo rescue retry self super then undef yield require require_relative include extend attr_accessor attr_reader attr_writer private protected public lambda proc")
                .control("if elsif else unless case when while until for break return raise")
                .constants("true false nil").line("#").block("=begin", "=end").dollar().annotations().build())
                .comments("#", "=begin", "=end").tabs(2, true).shebang("ruby"));
        reg(new Language("lua", "Lua", "lua luau rockspec", "", () -> CodeLexer.spec()
                .keywords("and function in local not or end then do goto")
                .control("if elseif else for while repeat until break return")
                .constants("true false nil self").builtins("print pairs ipairs require type tostring tonumber setmetatable getmetatable pcall error assert")
                .block("--[[", "]]").line("--").ml("[[").noCapsTypes().build())
                .comments("--", "--[[", "]]").shebang("lua"));
        reg(new Language("perl", "Perl", "pl pm t pod", "", () -> CodeLexer.spec()
                .keywords("my our local sub package use require no BEGIN END eq ne lt gt le ge cmp and or not x qw q qq")
                .control("if elsif else unless while until for foreach last next redo return die")
                .line("#").dollar().regex().build())
                .comments("#", null, null).shebang("perl"));
        reg(new Language("r", "R", "r R rmd", "", () -> CodeLexer.spec()
                .keywords("function in library require source")
                .control("if else for while repeat break next return")
                .constants("TRUE FALSE NULL NA NaN Inf T F").line("#").noCapsTypes().build())
                .comments("#", null, null).tabs(2, true).shebang("Rscript"));
        reg(new Language("shellscript", "Shell Script", "sh bash zsh ksh fish command bashrc zshrc profile", ".bashrc .zshrc .profile .bash_profile .bash_aliases .envrc PKGBUILD APKBUILD", () -> CodeLexer.spec()
                .keywords("function local export readonly declare typeset unset source alias in")
                .control("if then else elif fi for while until do done case esac select break continue return exit")
                .builtins("echo printf read cd pwd test exec eval set shift trap wait")
                .constants("true false").line("#").dollar().noCapsTypes().noCalls().dashes().build())
                .comments("#", null, null).tabs(2, true).shebang("sh", "bash", "zsh", "ksh", "dash", "ash", "fish"));
        reg(new Language("powershell", "PowerShell", "ps1 psm1 psd1", "", () -> CodeLexer.spec().ignoreCase()
                .keywords("function filter param begin process end class enum using module dynamicparam workflow parallel sequence inlinescript configuration hidden static")
                .control("if elseif else switch for foreach while do until break continue return throw try catch finally trap exit")
                .constants("$true $false $null").line("#").block("<#", "#>").dollar().dashes().noCapsTypes().build())
                .comments("#", "<#", "#>").shebang("pwsh"));
        reg(new Language("bat", "Batch", "bat cmd", "", () -> CodeLexer.spec().ignoreCase()
                .keywords("echo set setlocal endlocal call start pushd popd shift title cls pause exist defined not errorlevel equ neq lss leq gtr geq")
                .control("if else for in do goto exit").line("rem ", "REM ", "::").quotes("\"").noCapsTypes().noCalls().build())
                .comments("REM", null, null));
        reg(new Language("sql", "SQL", "sql ddl dml psql mysql pgsql hql", "", () -> CodeLexer.spec().ignoreCase()
                .keywords("select from where insert into values update set delete create table view index drop alter add column primary key foreign references unique not null default constraint join inner left right outer full cross on as and or in is like between exists distinct group by order having limit offset union all case when then else end begin commit rollback transaction with recursive returning database schema grant revoke trigger procedure function returns declare asc desc if replace truncate cascade")
                .types("int integer bigint smallint tinyint decimal numeric float real double varchar char text blob date time timestamp datetime boolean bool serial uuid json jsonb")
                .constants("true false null").line("--", "#").block("/*", "*/").quotes("'\"`").doubledQuotes().noCapsTypes().build())
                .comments("--", "/*", "*/"));
        reg(new Language("haskell", "Haskell", "hs lhs", "", () -> CodeLexer.spec()
                .keywords("module import qualified as hiding where let in do case of class instance data type newtype deriving family forall infix infixl infixr")
                .control("if then else").constants("True False Nothing Just").line("--").block("{-", "-}").build())
                .comments("--", "{-", "-}").tabs(2, true));
        reg(new Language("clojure", "Clojure", "clj cljs cljc edn", "", () -> CodeLexer.spec()
                .keywords("def defn defn- defmacro defprotocol defrecord deftype fn let loop recur ns require import if do when cond case quote")
                .constants("true false nil").line(";").quotes("\"").lisp().noCapsTypes().build())
                .comments(";", null, null).tabs(2, true));
        reg(new Language("lisp", "Lisp / Scheme", "lisp lsp el scm ss rkt", "", () -> CodeLexer.spec()
                .keywords("define defun defvar defparameter defmacro lambda let let* if cond when unless progn setq setf loop do begin quote")
                .constants("t nil #t #f").line(";").block("#|", "|#").quotes("\"").lisp().noCapsTypes().build())
                .comments(";", "#|", "|#").tabs(2, true));
        reg(new Language("julia", "Julia", "jl", "", () -> CodeLexer.spec()
                .keywords("function end module using import export struct mutable abstract type macro quote let local global const begin where in")
                .control("if elseif else for while break continue return try catch finally do")
                .constants("true false nothing missing").line("#").block("#=", "=#").ml("\"\"\"").dollar().build())
                .comments("#", "#=", "=#"));
        reg(new Language("pascal", "Pascal / Delphi", "pas pp dpr lpr", "", () -> CodeLexer.spec().ignoreCase()
                .keywords("program unit uses interface implementation var const type begin end procedure function record class object array of set string integer real boolean char with initialization finalization property private public protected published override virtual inherited")
                .control("if then else case for to downto do while repeat until exit break continue try except finally raise")
                .constants("true false nil").line("//").block("{", "}").block("(*", "*)").quotes("'").doubledQuotes().noCapsTypes().build())
                .comments("//", "{", "}"));
        reg(new Language("asm", "Assembly", "asm s S nasm inc", "", () -> CodeLexer.spec().ignoreCase()
                .keywords("section global extern db dw dd dq resb resw resd equ times org bits .text .data .bss .global .globl .section .align .byte .word .long .quad .ascii .asciz")
                .control("jmp je jne jz jnz jg jge jl jle ja jae jb jbe call ret loop b bl bx blx beq bne cbz cbnz")
                .line(";", "#", "//", "@").block("/*", "*/").noCapsTypes().noCalls().build())
                .comments(";", "/*", "*/"));
        reg(new Language("glsl", "GLSL / HLSL", "glsl vert frag geom comp tesc tese hlsl fx shader wgsl", "", () -> CodeLexer.spec()
                .keywords("uniform attribute varying in out inout const layout precision highp mediump lowp struct discard flat smooth centroid buffer shared")
                .control(C_CONTROL).types("void bool int uint float double vec2 vec3 vec4 ivec2 ivec3 ivec4 mat2 mat3 mat4 sampler2D samplerCube float2 float3 float4 float4x4")
                .constants("true false").line("//").block("/*", "*/").preproc().build())
                .comments("//", "/*", "*/"));

        // ---- Config & data
        reg(new Language("yaml", "YAML", "yaml yml", ".clang-format .clang-tidy", () -> CodeLexer.spec()
                .constants("true false null yes no on off ~").line("#").keys().noCalls().noCapsTypes().dashes().build())
                .comments("#", null, null).indentAfter(":").offSide().tabs(2, true));
        reg(new Language("toml", "TOML", "toml", "Cargo.lock Pipfile", () -> CodeLexer.spec()
                .constants("true false").line("#").ml("\"\"\"", "'''").keys().noCalls().noCapsTypes().dashes().build())
                .comments("#", null, null));
        reg(new Language("ini", "INI / Properties", "ini cfg conf properties prop editorconfig gitconfig desktop service env", ".editorconfig .gitconfig .env .npmrc gradle.properties local.properties", () -> CodeLexer.spec()
                .constants("true false").line("#", ";").keys().noCalls().noCapsTypes().dashes().rawStrings().build())
                .comments("#", null, null));
        reg(new Language("dockerfile", "Dockerfile", "dockerfile containerfile", "Dockerfile Containerfile", () -> CodeLexer.spec().ignoreCase()
                .keywords("from run cmd label maintainer expose env add copy entrypoint volume user workdir arg onbuild stopsignal healthcheck shell as")
                .line("#").dollar().noCapsTypes().noCalls().build())
                .comments("#", null, null));
        reg(new Language("makefile", "Makefile", "mk mak make", "Makefile makefile GNUmakefile", () -> CodeLexer.spec()
                .keywords("include define endef export unexport override ifeq ifneq ifdef ifndef else endif vpath .PHONY")
                .line("#").dollar().noCapsTypes().noCalls().dashes().build())
                .comments("#", null, null).tabs(4, false));
        reg(new Language("cmake", "CMake", "cmake", "CMakeLists.txt", () -> CodeLexer.spec().ignoreCase()
                .keywords("add_executable add_library target_link_libraries target_include_directories find_package include_directories project cmake_minimum_required set option message install add_subdirectory")
                .control("if elseif else endif foreach endforeach while endwhile function endfunction macro endmacro return")
                .line("#").dollar().noCapsTypes().build())
                .comments("#", null, null));
        reg(new Language("markdown", "Markdown", "md markdown mdown mkd mdx", "README LICENSE", MarkdownLexer::new)
                .comments(null, "<!--", "-->").pairs("([{\"`", ")]}\"`").tabs(2, true));
        reg(new Language("diff", "Diff", "diff patch rej", "", () -> new Lexer() {
            @Override
            public int lex(char[] s, int len, int state, Tokens out) {
                int st = Tok.PLAIN;
                if (len > 0) {
                    char c = s[0];
                    if (c == '+') st = len > 2 && s[1] == '+' && s[2] == '+' ? Tok.HEADING : Tok.DIFF_ADD;
                    else if (c == '-') st = len > 2 && s[1] == '-' && s[2] == '-' ? Tok.HEADING : Tok.DIFF_DEL;
                    else if (c == '@') st = Tok.CONTROL;
                    else if (c == 'd' || c == 'i') st = Tok.HEADING;
                }
                out.add(0, st);
                return 0;
            }
        }));
        reg(new Language("gitignore", "Ignore", "gitignore dockerignore npmignore eslintignore gitattributes", ".gitignore .dockerignore .npmignore .gitattributes .gitmodules", () -> CodeLexer.spec()
                .line("#").quotes("").noCalls().noCapsTypes().build())
                .comments("#", null, null));
        reg(new Language("csv", "CSV", "csv tsv", "", () -> CodeLexer.spec().quotes("\"").noCalls().noCapsTypes().doubledQuotes().build()));
        reg(new Language("latex", "LaTeX", "tex sty cls bib ltx", "", () -> CodeLexer.spec()
                .line("%").quotes("").dollar().noCapsTypes().noCalls().build())
                .comments("%", null, null));
        reg(new Language("proto", "Protocol Buffers", "proto", "", () -> CodeLexer.spec()
                .keywords("syntax package import option message enum service rpc returns oneof map repeated optional required reserved extend stream")
                .types("double float int32 int64 uint32 uint64 sint32 sint64 fixed32 fixed64 sfixed32 sfixed64 bool string bytes")
                .constants("true false").line("//").block("/*", "*/").build())
                .comments("//", "/*", "*/").tabs(2, true));
        reg(new Language("graphql", "GraphQL", "graphql gql", "", () -> CodeLexer.spec()
                .keywords("query mutation subscription fragment on type input enum interface union scalar schema extend implements directive")
                .constants("true false null").line("#").ml("\"\"\"").quotes("\"").dollar().annotations().build())
                .comments("#", null, null).tabs(2, true));
    }

    private static void reg(Language l) {
        ALL.add(l);
        BY_ID.put(l.id, l);
        for (String e : l.extensions) BY_EXT.put(e.toLowerCase(Locale.ROOT), l);
        for (String n : l.fileNames) BY_NAME.put(n, l);
    }

    /** Registers a language contributed later (e.g. by an extension). */
    public static synchronized void register(Language l) {
        Language old = BY_ID.get(l.id);
        if (old != null) ALL.remove(old);
        reg(l);
    }

    public static Language find(String id) {
        Language l = BY_ID.get(id);
        return l != null ? l : PLAIN;
    }

    public static Language byId(String id) {
        return BY_ID.get(id);
    }

    /** Chooses the language for a file from its name and (optionally) its first line. */
    public static Language forFile(String fileName, String firstLine) {
        Language l = BY_NAME.get(fileName);
        if (l != null) return l;
        String lower = fileName.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        if (dot >= 0) {
            l = BY_EXT.get(lower.substring(dot + 1));
            if (l == null && dot == 0) l = BY_EXT.get(lower.substring(1));
            if (l != null) return l;
        }
        if (lower.startsWith("dockerfile")) return find("dockerfile");
        if (firstLine != null && firstLine.startsWith("#!")) {
            String sb = firstLine.substring(2).trim();
            String[] parts = sb.split("[\\s/]+");
            for (int i = parts.length - 1; i >= 0; i--) {
                String p = parts[i];
                for (Language x : ALL) {
                    for (String in : x.interpreters) if (p.equals(in) || p.startsWith(in + ".")) return x;
                }
            }
        }
        if (firstLine != null && firstLine.startsWith("<?xml")) return find("xml");
        if (firstLine != null && firstLine.startsWith("<?php")) return find("php");
        return PLAIN;
    }

    public static List<Language> all() {
        ArrayList<Language> l = new ArrayList<Language>(ALL);
        Collections.sort(l, new Comparator<Language>() {
            @Override
            public int compare(Language a, Language b) {
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        return l;
    }
}
