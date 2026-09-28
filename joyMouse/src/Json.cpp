#include "Json.h"

#include <cstdio>
#include <cstdlib>

namespace joymouse {

class JsonParser {
public:
    explicit JsonParser(std::string_view text) : text_(text) {}

    std::optional<JsonValue> document(std::string* error) {
        JsonValue v;
        if (!value(&v, 0)) {
            if (error) *error = error_;
            return std::nullopt;
        }
        skipSpace();
        if (pos_ != text_.size()) {
            if (error) *error = where("unexpected text after the document");
            return std::nullopt;
        }
        return v;
    }

private:
    // Deep enough for any real profile, shallow enough to never exhaust the stack.
    static constexpr int kMaxDepth = 32;

    std::string where(const char* what) const {
        char buf[96];
        std::snprintf(buf, sizeof(buf), "%s at offset %zu", what, pos_);
        return buf;
    }

    bool fail(const char* what) {
        if (error_.empty()) error_ = where(what);
        return false;
    }

    void skipSpace() {
        while (pos_ < text_.size() &&
               (text_[pos_] == ' ' || text_[pos_] == '\t' || text_[pos_] == '\n' || text_[pos_] == '\r')) {
            ++pos_;
        }
    }

    bool literal(std::string_view word) {
        if (text_.substr(pos_, word.size()) != word) return fail("unknown literal");
        pos_ += word.size();
        return true;
    }

    bool value(JsonValue* out, int depth) {
        if (depth > kMaxDepth) return fail("nested too deeply");
        skipSpace();
        if (pos_ >= text_.size()) return fail("unexpected end");
        const char c = text_[pos_];
        switch (c) {
            case '{': return object(out, depth);
            case '[': return array(out, depth);
            case '"':
                out->type_ = JsonValue::Type::String;
                return string(&out->string_);
            case 't':
                out->type_ = JsonValue::Type::Bool;
                out->bool_ = true;
                return literal("true");
            case 'f':
                out->type_ = JsonValue::Type::Bool;
                out->bool_ = false;
                return literal("false");
            case 'n':
                out->type_ = JsonValue::Type::Null;
                return literal("null");
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return number(out);
                return fail("unexpected character");
        }
    }

    bool object(JsonValue* out, int depth) {
        out->type_ = JsonValue::Type::Object;
        ++pos_;  // {
        skipSpace();
        if (pos_ < text_.size() && text_[pos_] == '}') {
            ++pos_;
            return true;
        }
        while (true) {
            skipSpace();
            if (pos_ >= text_.size() || text_[pos_] != '"') return fail("expected a member name");
            std::string key;
            if (!string(&key)) return false;
            skipSpace();
            if (pos_ >= text_.size() || text_[pos_] != ':') return fail("expected ':'");
            ++pos_;
            JsonValue member;
            if (!value(&member, depth + 1)) return false;
            out->members_[key] = std::move(member);
            skipSpace();
            if (pos_ >= text_.size()) return fail("unexpected end in an object");
            if (text_[pos_] == ',') {
                ++pos_;
                continue;
            }
            if (text_[pos_] == '}') {
                ++pos_;
                return true;
            }
            return fail("expected ',' or '}'");
        }
    }

    bool array(JsonValue* out, int depth) {
        out->type_ = JsonValue::Type::Array;
        ++pos_;  // [
        skipSpace();
        if (pos_ < text_.size() && text_[pos_] == ']') {
            ++pos_;
            return true;
        }
        while (true) {
            JsonValue item;
            if (!value(&item, depth + 1)) return false;
            out->items_.push_back(std::move(item));
            skipSpace();
            if (pos_ >= text_.size()) return fail("unexpected end in an array");
            if (text_[pos_] == ',') {
                ++pos_;
                continue;
            }
            if (text_[pos_] == ']') {
                ++pos_;
                return true;
            }
            return fail("expected ',' or ']'");
        }
    }

    static int hexDigit(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    static void appendUtf8(std::string* s, unsigned cp) {
        if (cp < 0x80) {
            s->push_back(static_cast<char>(cp));
        } else if (cp < 0x800) {
            s->push_back(static_cast<char>(0xC0 | (cp >> 6)));
            s->push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else {
            s->push_back(static_cast<char>(0xE0 | (cp >> 12)));
            s->push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            s->push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        }
    }

    bool string(std::string* out) {
        ++pos_;  // "
        while (pos_ < text_.size()) {
            const char c = text_[pos_++];
            if (c == '"') return true;
            if (c != '\\') {
                out->push_back(c);
                continue;
            }
            if (pos_ >= text_.size()) break;
            const char e = text_[pos_++];
            switch (e) {
                case '"': out->push_back('"'); break;
                case '\\': out->push_back('\\'); break;
                case '/': out->push_back('/'); break;
                case 'b': out->push_back('\b'); break;
                case 'f': out->push_back('\f'); break;
                case 'n': out->push_back('\n'); break;
                case 'r': out->push_back('\r'); break;
                case 't': out->push_back('\t'); break;
                case 'u': {
                    if (pos_ + 4 > text_.size()) return fail("short \\u escape");
                    unsigned cp = 0;
                    for (int i = 0; i < 4; ++i) {
                        const int d = hexDigit(text_[pos_++]);
                        if (d < 0) return fail("bad \\u escape");
                        cp = cp * 16 + static_cast<unsigned>(d);
                    }
                    // Surrogate pairs aren't needed for anything the daemon reads.
                    appendUtf8(out, cp);
                    break;
                }
                default:
                    return fail("bad escape");
            }
        }
        return fail("unterminated string");
    }

    bool number(JsonValue* out) {
        const size_t start = pos_;
        if (text_[pos_] == '-') ++pos_;
        auto digits = [&] {
            const size_t from = pos_;
            while (pos_ < text_.size() && text_[pos_] >= '0' && text_[pos_] <= '9') ++pos_;
            return pos_ > from;
        };
        if (!digits()) return fail("bad number");
        if (pos_ < text_.size() && text_[pos_] == '.') {
            ++pos_;
            if (!digits()) return fail("bad number");
        }
        if (pos_ < text_.size() && (text_[pos_] == 'e' || text_[pos_] == 'E')) {
            ++pos_;
            if (pos_ < text_.size() && (text_[pos_] == '+' || text_[pos_] == '-')) ++pos_;
            if (!digits()) return fail("bad number");
        }
        const std::string copy(text_.substr(start, pos_ - start));
        out->type_ = JsonValue::Type::Number;
        out->number_ = std::strtod(copy.c_str(), nullptr);
        return true;
    }

    std::string_view text_;
    size_t pos_ = 0;
    std::string error_;
};

std::optional<bool> JsonValue::asBool() const {
    if (type_ != Type::Bool) return std::nullopt;
    return bool_;
}

std::optional<double> JsonValue::asNumber() const {
    if (type_ != Type::Number) return std::nullopt;
    return number_;
}

std::optional<std::string> JsonValue::asString() const {
    if (type_ != Type::String) return std::nullopt;
    return string_;
}

const JsonValue* JsonValue::get(const std::string& key) const {
    if (type_ != Type::Object) return nullptr;
    const auto it = members_.find(key);
    return it == members_.end() ? nullptr : &it->second;
}

std::optional<JsonValue> JsonValue::parse(std::string_view text, std::string* error) {
    return JsonParser(text).document(error);
}

}  // namespace joymouse
