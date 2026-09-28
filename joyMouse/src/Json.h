#pragma once

#include <map>
#include <memory>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace joymouse {

// A small JSON reader, enough for the touch control profiles. No writer: the
// settings app writes the files.
class JsonValue {
public:
    enum class Type { Null, Bool, Number, String, Array, Object };

    Type type() const { return type_; }
    bool isObject() const { return type_ == Type::Object; }
    bool isArray() const { return type_ == Type::Array; }

    std::optional<bool> asBool() const;
    std::optional<double> asNumber() const;
    std::optional<std::string> asString() const;
    const std::vector<JsonValue>& items() const { return items_; }
    // Member of an object; nullptr if missing or not an object.
    const JsonValue* get(const std::string& key) const;

    // Parses a whole document. On failure returns nullopt and describes the
    // problem in `error` (if given).
    static std::optional<JsonValue> parse(std::string_view text, std::string* error = nullptr);

private:
    friend class JsonParser;

    Type type_ = Type::Null;
    bool bool_ = false;
    double number_ = 0.0;
    std::string string_;
    std::vector<JsonValue> items_;
    std::map<std::string, JsonValue> members_;
};

}  // namespace joymouse
