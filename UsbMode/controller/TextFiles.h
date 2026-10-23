#pragma once
#include "Controller.h"
namespace controller {
Bytes soundRequest(const std::string& root, const Bytes& request);
Bytes textRequest(const std::string& root, const Bytes& request);
}
